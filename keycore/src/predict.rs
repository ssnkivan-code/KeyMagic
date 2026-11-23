//! Word prediction (completions, typo fixes, next word) and emoji prediction.
//!
//! * Base dictionary: `word \t frequency` lines loaded from the app's assets.
//! * Learning: every word you finish typing is counted, plus which word followed
//!   which ("good" → "morning"), so suggestions adapt to how you write
//!   (English, Luganda, names…). Saved/loaded with `export_user` / `import_user`.
//! * Emoji: `group \t emoji \t name \t keywords` lines; typing "love", "happy",
//!   "pizza"… suggests matching emojis, boosted by the ones you use.

use std::collections::HashMap;

const USER_WEIGHT: u64 = 2_000;
const BIGRAM_WEIGHT: u64 = 40_000;
const MAX_BIGRAMS: usize = 30_000;

struct Entry {
    key: String,     // lowercase
    display: String, // as stored (keeps "Kampala", "I")
    freq: u64,
}

struct EmojiInfo {
    emoji: String,
    order: usize,
}

/// A parsed word list, ready to install into a `Predictor`.
pub struct Dictionary {
    entries: Vec<Entry>,
    index: HashMap<String, usize>,
}

impl Dictionary {
    pub fn parse(data: &str) -> Self {
        let mut map: HashMap<String, Entry> = HashMap::new();
        for line in data.lines() {
            let mut p = line.split('\t');
            let Some(word) = p.next().map(str::trim).filter(|w| !w.is_empty()) else { continue };
            let freq = p.next().and_then(|f| f.trim().parse::<u64>().ok()).unwrap_or(1);
            let key = word.to_lowercase();
            let e = map.entry(key.clone()).or_insert(Entry { key, display: word.to_string(), freq: 0 });
            if freq > e.freq {
                e.freq = freq;
                // prefer the lowercase spelling for common words ("may" over "May")
                if word.chars().next().is_some_and(char::is_lowercase) || e.display.is_empty() {
                    e.display = word.to_string();
                }
            }
        }
        let mut entries: Vec<Entry> = map.into_values().collect();
        entries.sort_by(|a, b| a.key.cmp(&b.key));
        let index = entries.iter().enumerate().map(|(i, e)| (e.key.clone(), i)).collect();
        Dictionary { entries, index }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

#[derive(Default)]
pub struct Predictor {
    dict: Vec<Entry>, // sorted by key
    index: HashMap<String, usize>,
    user: HashMap<String, (String, u64)>,         // key -> (display, count)
    bigrams: HashMap<(String, String), u64>,      // (prev, word) -> count
    emojis: Vec<EmojiInfo>,
    emoji_words: HashMap<String, Vec<(usize, u64)>>, // word -> (emoji idx, weight)
    emoji_use: HashMap<String, u64>,
}

/// What is around the cursor.
struct Context {
    partial: String, // word being typed (may be empty)
    prev: Option<String>,
}

fn is_word_char(c: char) -> bool {
    c.is_alphanumeric() || c == '\''
}

fn parse_context(before: &str) -> Context {
    let chars: Vec<char> = before.chars().collect();
    let mut end = chars.len();
    let mut start = end;
    while start > 0 && is_word_char(chars[start - 1]) {
        start -= 1;
    }
    let partial: String = chars[start..end].iter().collect();
    // previous word: skip spaces only (a full stop breaks the link)
    end = start;
    while end > 0 && chars[end - 1] == ' ' {
        end -= 1;
    }
    let mut prev = None;
    if end > 0 && is_word_char(chars[end - 1]) && (end < start || partial.is_empty() && end < chars.len()) {
        let mut s = end;
        while s > 0 && is_word_char(chars[s - 1]) {
            s -= 1;
        }
        let w: String = chars[s..end].iter().collect();
        if w.chars().any(char::is_alphabetic) {
            prev = Some(w.to_lowercase());
        }
    }
    Context { partial, prev }
}

fn match_case(word: &str, typed: &str) -> String {
    let mut t = typed.chars();
    let first_upper = t.next().is_some_and(char::is_uppercase);
    let all_upper = typed.chars().count() > 1 && typed.chars().filter(|c| c.is_alphabetic()).all(char::is_uppercase);
    if all_upper {
        word.to_uppercase()
    } else if first_upper {
        let mut c = word.chars();
        match c.next() {
            Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
            None => String::new(),
        }
    } else {
        word.to_string()
    }
}

impl Predictor {
    pub fn new() -> Self {
        Self::default()
    }

    // ---------------------------------------------------------------- loading

    pub fn load_dictionary(&mut self, data: &str) {
        self.install_dictionary(Dictionary::parse(data));
    }

    /// Swap in a dictionary built elsewhere (lets the app parse it off the UI thread
    /// without holding the engine lock).
    pub fn install_dictionary(&mut self, d: Dictionary) {
        self.dict = d.entries;
        self.index = d.index;
    }

    pub fn load_emoji(&mut self, data: &str) {
        self.emojis.clear();
        self.emoji_words.clear();
        for line in data.lines() {
            let p: Vec<&str> = line.split('\t').collect();
            if p.len() < 3 {
                continue;
            }
            let idx = self.emojis.len();
            self.emojis.push(EmojiInfo { emoji: p[1].to_string(), order: idx });
            let name = p[2].to_lowercase();
            let name_words: Vec<&str> =
                name.split(|c: char| !c.is_alphanumeric()).filter(|w| w.len() > 1).collect();
            let mut add = |w: &str, weight: u64| {
                let list = self.emoji_words.entry(w.to_string()).or_default();
                if let Some(x) = list.iter_mut().find(|(i, _)| *i == idx) {
                    x.1 = x.1.max(weight);
                } else {
                    list.push((idx, weight));
                }
            };
            // faces & hearts are what people mostly want suggested
            let bonus = if p[0] == "smileys" { 2 } else { 0 };
            let flag = p[0] == "flags";
            if name_words.len() == 1 {
                add(name_words[0], 10 + bonus);
            }
            for w in &name_words {
                if !flag || name_words.len() == 1 {
                    add(w, 3 + bonus);
                }
            }
            if let Some(kws) = p.get(3) {
                for kw in kws.split(',') {
                    let kw = kw.trim().to_lowercase();
                    if kw.chars().count() >= 3 && !kw.contains(' ') && !flag {
                        add(&kw, 2 + bonus);
                    }
                }
            }
        }
    }

    pub fn dictionary_size(&self) -> usize {
        self.dict.len()
    }

    // ------------------------------------------------------------- suggestions

    fn freq_of(&self, key: &str) -> u64 {
        self.index.get(key).map_or(0, |&i| self.dict[i].freq)
    }

    fn user_of(&self, key: &str) -> u64 {
        self.user.get(key).map_or(0, |u| u.1)
    }

    fn display_of(&self, key: &str) -> String {
        if let Some(&i) = self.index.get(key) {
            return self.dict[i].display.clone();
        }
        self.user.get(key).map_or_else(|| key.to_string(), |u| u.0.clone())
    }

    fn score(&self, key: &str, prev: Option<&str>) -> u64 {
        let mut s = self.freq_of(key) + self.user_of(key) * USER_WEIGHT;
        if let Some(p) = prev {
            s += self.bigrams.get(&(p.to_string(), key.to_string())).copied().unwrap_or(0) * BIGRAM_WEIGHT;
        }
        s
    }

    fn is_known(&self, key: &str) -> bool {
        self.index.contains_key(key) || self.user.contains_key(key)
    }

    /// Up to `max` word suggestions for the text before the cursor.
    pub fn suggest(&self, before_cursor: &str, max: usize) -> Vec<String> {
        let ctx = parse_context(before_cursor);
        let prev = ctx.prev.as_deref();

        if ctx.partial.is_empty() {
            // Next-word prediction from what you've typed before.
            let Some(p) = prev else { return Vec::new() };
            let mut next: Vec<(&String, u64)> =
                self.bigrams.iter().filter(|((a, _), _)| a == p).map(|((_, b), &c)| (b, c)).collect();
            next.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(b.0)));
            return next.into_iter().take(max).map(|(w, _)| self.display_of(w)).collect();
        }

        let typed = ctx.partial.as_str();
        let key = typed.to_lowercase();
        let mut scored: HashMap<String, u64> = HashMap::new();

        // 1. completions from the dictionary
        let start = self.dict.partition_point(|e| e.key.as_str() < key.as_str());
        for e in self.dict[start..].iter().take_while(|e| e.key.starts_with(&key)) {
            scored.insert(e.key.clone(), self.score(&e.key, prev));
        }
        // 2. completions from your own words
        for k in self.user.keys().filter(|k| k.starts_with(&key)) {
            scored.insert(k.clone(), self.score(k, prev));
        }
        // 3. typo fixes (one letter off) for words of 3+ letters; they rank below
        //    real completions and must not be shorter than what you typed.
        let typed_len = key.chars().count();
        if typed_len >= 3 {
            for cand in edits1(&key) {
                if cand.chars().count() >= typed_len && self.is_known(&cand) && !scored.contains_key(&cand) {
                    scored.insert(cand.clone(), self.score(&cand, prev) / 50);
                }
            }
        }
        // 4. the exact word you typed comes first if it's a common word or one of yours
        //    (not rare dictionary entries like "Th" or "Hel")
        if self.freq_of(&key) >= 10 || self.user_of(&key) > 0 {
            if let Some(v) = scored.get_mut(&key) {
                *v = u64::MAX;
            }
        }

        let mut list: Vec<(String, u64)> = scored.into_iter().collect();
        list.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.len().cmp(&b.0.len())).then_with(|| a.0.cmp(&b.0)));

        let mut out: Vec<String> = Vec::new();
        for (k, _) in list {
            if out.len() >= max {
                break;
            }
            let shown = self.display_of(&k);
            let shown = if shown.chars().next().is_some_and(char::is_uppercase) && shown != "I" {
                shown // proper noun – keep its capital
            } else {
                match_case(&shown, typed)
            };
            if !out.contains(&shown) {
                out.push(shown);
            }
        }
        // If there's room, also offer exactly what you typed when it's an unknown
        // word of 4+ letters, so you can keep it (names, Luganda words…).
        if out.len() < max && !self.is_known(&key) && key.chars().count() >= 4 && !out.iter().any(|w| w == typed) {
            out.push(typed.to_string());
        }
        out.truncate(max);
        out
    }

    /// Up to `max` emojis for the word being typed (or the word just finished).
    pub fn suggest_emoji(&self, before_cursor: &str, max: usize) -> Vec<String> {
        let ctx = parse_context(before_cursor);
        let word = if ctx.partial.is_empty() { ctx.prev.unwrap_or_default() } else { ctx.partial.to_lowercase() };
        if word.chars().count() < 3 {
            return Vec::new();
        }
        let Some(list) = self.emoji_words.get(&word) else { return Vec::new() };
        let mut ranked: Vec<(u64, usize)> = list
            .iter()
            .map(|&(i, w)| {
                let used = self.emoji_use.get(&self.emojis[i].emoji).copied().unwrap_or(0);
                (w * 100 + used * 300, i)
            })
            .collect();
        ranked.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| self.emojis[a.1].order.cmp(&self.emojis[b.1].order)));
        ranked.into_iter().take(max).map(|(_, i)| self.emojis[i].emoji.clone()).collect()
    }

    // ---------------------------------------------------------------- learning

    /// Call right after a space / punctuation was typed. Learns the word that
    /// was just finished and which word came before it.
    pub fn learn(&mut self, before_cursor: &str) {
        // Drop the separator(s) just typed, then reuse the parser.
        let trimmed = before_cursor.trim_end_matches(|c: char| !is_word_char(c));
        let ctx = parse_context(trimmed);
        let word = ctx.partial;
        if word.is_empty() || word.chars().any(|c| c.is_ascii_digit()) || word.chars().count() > 30 {
            return;
        }
        let key = word.to_lowercase();
        let display = if self.index.contains_key(&key) { self.dict[self.index[&key]].display.clone() } else { word.clone() };
        let e = self.user.entry(key.clone()).or_insert((display, 0));
        e.1 += 1;
        if let Some(p) = ctx.prev {
            *self.bigrams.entry((p, key)).or_insert(0) += 1;
            if self.bigrams.len() > MAX_BIGRAMS {
                self.bigrams.retain(|_, c| *c > 1);
            }
        }
    }

    /// Forget a word you don't want suggested (long-press a suggestion).
    pub fn forget(&mut self, word: &str) {
        let key = word.to_lowercase();
        self.user.remove(&key);
        self.bigrams.retain(|(a, b), _| a != &key && b != &key);
    }

    /// Forget everything learned (words, word pairs, emoji use).
    pub fn clear_user(&mut self) {
        self.user.clear();
        self.bigrams.clear();
        self.emoji_use.clear();
    }

    pub fn record_emoji(&mut self, emoji: &str) {
        *self.emoji_use.entry(emoji.to_string()).or_insert(0) += 1;
    }

    /// Most used emojis first (for the "Recent" tab).
    pub fn frequent_emojis(&self, max: usize) -> Vec<String> {
        let mut v: Vec<(&String, &u64)> = self.emoji_use.iter().collect();
        v.sort_by(|a, b| b.1.cmp(a.1).then_with(|| a.0.cmp(b.0)));
        v.into_iter().take(max).map(|(e, _)| e.clone()).collect()
    }

    pub fn export_user(&self) -> String {
        let mut out = String::new();
        for (k, (d, c)) in &self.user {
            out.push_str(&format!("W\t{k}\t{d}\t{c}\n"));
        }
        for ((a, b), c) in &self.bigrams {
            out.push_str(&format!("B\t{a}\t{b}\t{c}\n"));
        }
        for (e, c) in &self.emoji_use {
            out.push_str(&format!("E\t{e}\t{c}\n"));
        }
        out
    }

    pub fn import_user(&mut self, data: &str) {
        for line in data.lines() {
            let p: Vec<&str> = line.split('\t').collect();
            match p.as_slice() {
                ["W", k, d, c] => {
                    if let Ok(c) = c.parse() {
                        self.user.insert(k.to_string(), (d.to_string(), c));
                    }
                }
                ["B", a, b, c] => {
                    if let Ok(c) = c.parse() {
                        self.bigrams.insert((a.to_string(), b.to_string()), c);
                    }
                }
                ["E", e, c] => {
                    if let Ok(c) = c.parse() {
                        self.emoji_use.insert(e.to_string(), c);
                    }
                }
                _ => {}
            }
        }
    }
}

/// All strings one edit away (delete, swap, replace, insert) using a–z.
fn edits1(word: &str) -> Vec<String> {
    let chars: Vec<char> = word.chars().collect();
    let n = chars.len();
    let mut out = Vec::with_capacity(54 * n + 25);
    for i in 0..n {
        let mut v = chars.clone();
        v.remove(i);
        out.push(v.iter().collect());
    }
    for i in 0..n.saturating_sub(1) {
        let mut v = chars.clone();
        v.swap(i, i + 1);
        out.push(v.iter().collect());
    }
    for i in 0..n {
        for c in 'a'..='z' {
            if c != chars[i] {
                let mut v = chars.clone();
                v[i] = c;
                out.push(v.iter().collect());
            }
        }
    }
    for i in 0..=n {
        for c in 'a'..='z' {
            let mut v = chars.clone();
            v.insert(i, c);
            out.push(v.iter().collect());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn predictor() -> Predictor {
        let mut p = Predictor::new();
        p.load_dictionary("the\t100000\nthere\t20000\nthey\t30000\nthem\t15000\nthen\t18000\nhello\t5000\nhelp\t9000\nmorning\t3000\ngood\t40000\nKampala\t800\nI\t90000\nmay\t8000\nMay\t500\n");
        p.load_emoji("smileys\t😀\tgrinning face\thappy,smile\nsmileys\t😂\tface with tears of joy\tlaugh,happy\npeople\t❤️\tred heart\tlove\nfood\t🍕\tpizza\tcheese,food\n");
        p
    }

    #[test]
    fn completes_by_frequency() {
        let p = predictor();
        assert_eq!(p.suggest("I want to go th", 3), vec!["the", "they", "there"]);
        assert_eq!(p.suggest("hel", 3), vec!["help", "hello"]);
    }

    #[test]
    fn keeps_typed_unknown_word_first() {
        let p = predictor();
        assert_eq!(p.suggest("webale", 3)[0], "webale");
    }

    #[test]
    fn matches_capitals() {
        let p = predictor();
        assert_eq!(p.suggest("Th", 1), vec!["The"]);
        assert_eq!(p.suggest("TH", 1), vec!["THE"]);
        assert_eq!(p.suggest("kamp", 1), vec!["Kampala"]);
        assert_eq!(p.suggest("ma", 1), vec!["may"]);
    }

    #[test]
    fn fixes_typos() {
        let p = predictor();
        let s = p.suggest("helo", 3);
        assert!(s.contains(&"hello".to_string()), "{s:?}");
        let s = p.suggest("teh", 3);
        assert!(s.contains(&"the".to_string()), "{s:?}");
    }

    #[test]
    fn learns_words_and_next_word() {
        let mut p = predictor();
        p.learn("good morning ");
        p.learn("good morning ");
        assert_eq!(p.suggest("good ", 3), vec!["morning"]);
        // a learned word now completes
        p.learn("webale ");
        p.learn("webale nyo ");
        assert!(p.suggest("weba", 3).contains(&"webale".to_string()));
        // bigram boosts ranking: after "good", "m" -> morning first
        assert_eq!(p.suggest("good m", 1), vec!["morning"]);
        p.forget("morning");
        assert!(p.suggest("good ", 3).is_empty());
    }

    #[test]
    fn sentence_end_breaks_next_word() {
        let mut p = predictor();
        p.learn("good. morning ");
        assert!(p.suggest("good ", 3).is_empty());
    }

    #[test]
    fn does_not_learn_numbers() {
        let mut p = predictor();
        p.learn("0772123456 ");
        assert!(p.export_user().is_empty());
    }

    #[test]
    fn user_data_round_trip() {
        let mut p = predictor();
        p.learn("good morning ");
        p.record_emoji("🍕");
        let data = p.export_user();
        let mut q = predictor();
        q.import_user(&data);
        assert_eq!(q.suggest("good ", 3), vec!["morning"]);
        assert_eq!(q.frequent_emojis(5), vec!["🍕"]);
    }

    #[test]
    fn emoji_suggestions() {
        let mut p = predictor();
        assert_eq!(p.suggest_emoji("I love", 2), vec!["❤️"]);
        assert_eq!(p.suggest_emoji("pizza ", 2), vec!["🍕"]);
        let happy = p.suggest_emoji("so happy", 2);
        assert_eq!(happy.len(), 2);
        p.record_emoji("😂");
        p.record_emoji("😂");
        assert_eq!(p.suggest_emoji("so happy", 1), vec!["😂"]);
        assert!(p.suggest_emoji("xyz", 2).is_empty());
    }
}

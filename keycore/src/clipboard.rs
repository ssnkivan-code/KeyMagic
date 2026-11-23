//! Clipboard history: the 10 most recent copied items (text, numbers, images,
//! screenshots). Adding an 11th item drops the oldest. Re-copying something that
//! is already in the list moves it to the top instead of duplicating it.
//!
//! Image items store a file path; when an image item is dropped, the path is
//! returned so the Android side can delete the file.

pub const MAX_ITEMS: usize = 10;

pub const KIND_TEXT: i32 = 0;
pub const KIND_IMAGE: i32 = 1;
pub const KIND_SCREENSHOT: i32 = 2;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Item {
    pub kind: i32,
    /// Text for text items, a file path for images/screenshots.
    pub content: String,
    pub time_ms: i64,
}

#[derive(Default)]
pub struct History {
    items: Vec<Item>, // newest first
}

impl History {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.items.len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    pub fn get(&self, i: usize) -> Option<&Item> {
        self.items.get(i)
    }

    /// Adds an item at the top. Returns file paths that are no longer used
    /// (dropped oldest image, or a duplicate image path), newline-separated.
    pub fn add(&mut self, kind: i32, content: &str, time_ms: i64) -> String {
        let mut freed = Vec::new();
        if content.trim().is_empty() {
            return String::new();
        }
        if kind == KIND_TEXT {
            if let Some(pos) = self.items.iter().position(|it| it.kind == KIND_TEXT && it.content == content) {
                let mut it = self.items.remove(pos);
                it.time_ms = time_ms;
                self.items.insert(0, it);
                return String::new();
            }
        } else if let Some(pos) = self.items.iter().position(|it| it.kind != KIND_TEXT && it.content == content) {
            self.items.remove(pos);
        }
        self.items.insert(0, Item { kind, content: content.to_string(), time_ms });
        while self.items.len() > MAX_ITEMS {
            let old = self.items.pop().expect("non-empty");
            if old.kind != KIND_TEXT {
                freed.push(old.content);
            }
        }
        freed.join("\n")
    }

    /// Moves item `i` to the top (used when you paste it). Returns false if out of range.
    pub fn touch(&mut self, i: usize, time_ms: i64) -> bool {
        if i >= self.items.len() {
            return false;
        }
        let mut it = self.items.remove(i);
        it.time_ms = time_ms;
        self.items.insert(0, it);
        true
    }

    /// Removes item `i`; returns its file path if it was an image.
    pub fn remove(&mut self, i: usize) -> String {
        if i >= self.items.len() {
            return String::new();
        }
        let it = self.items.remove(i);
        if it.kind != KIND_TEXT {
            it.content
        } else {
            String::new()
        }
    }

    /// Removes everything; returns all image paths, newline-separated.
    pub fn clear(&mut self) -> String {
        let paths: Vec<String> =
            self.items.drain(..).filter(|it| it.kind != KIND_TEXT).map(|it| it.content).collect();
        paths.join("\n")
    }

    /// One line per item: `kind \t time \t escaped content`.
    pub fn export(&self) -> String {
        let mut out = String::new();
        for it in &self.items {
            out.push_str(&format!("{}\t{}\t{}\n", it.kind, it.time_ms, escape(&it.content)));
        }
        out
    }

    pub fn import(&mut self, data: &str) {
        self.items.clear();
        for line in data.lines() {
            let mut parts = line.splitn(3, '\t');
            let (Some(k), Some(t), Some(c)) = (parts.next(), parts.next(), parts.next()) else {
                continue;
            };
            let (Ok(kind), Ok(time_ms)) = (k.parse::<i32>(), t.parse::<i64>()) else {
                continue;
            };
            if self.items.len() < MAX_ITEMS {
                self.items.push(Item { kind, content: unescape(c), time_ms });
            }
        }
    }
}

fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for ch in s.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            _ => out.push(ch),
        }
    }
    out
}

fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(ch) = it.next() {
        if ch == '\\' {
            match it.next() {
                Some('n') => out.push('\n'),
                Some('r') => out.push('\r'),
                Some('t') => out.push('\t'),
                Some(other) => out.push(other),
                None => out.push('\\'),
            }
        } else {
            out.push(ch);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_ten_newest_and_frees_old_images() {
        let mut h = History::new();
        assert_eq!(h.add(KIND_SCREENSHOT, "/clip/shot0.png", 0), "");
        for i in 1..10 {
            h.add(KIND_TEXT, &format!("text {i}"), i);
        }
        assert_eq!(h.len(), 10);
        // 11th item pushes out the oldest (the screenshot) and returns its path
        assert_eq!(h.add(KIND_TEXT, "0772 123456", 10), "/clip/shot0.png");
        assert_eq!(h.len(), 10);
        assert_eq!(h.get(0).unwrap().content, "0772 123456");
        assert_eq!(h.get(9).unwrap().content, "text 1");
    }

    #[test]
    fn duplicate_text_moves_to_top() {
        let mut h = History::new();
        h.add(KIND_TEXT, "a", 1);
        h.add(KIND_TEXT, "b", 2);
        h.add(KIND_TEXT, "a", 3);
        assert_eq!(h.len(), 2);
        assert_eq!(h.get(0).unwrap().content, "a");
        assert_eq!(h.get(0).unwrap().time_ms, 3);
    }

    #[test]
    fn ignores_blank() {
        let mut h = History::new();
        h.add(KIND_TEXT, "   ", 1);
        assert!(h.is_empty());
    }

    #[test]
    fn export_import_round_trip() {
        let mut h = History::new();
        h.add(KIND_TEXT, "line1\nline2\twith tab \\ slash", 5);
        h.add(KIND_IMAGE, "/clip/a.png", 6);
        let data = h.export();
        let mut h2 = History::new();
        h2.import(&data);
        assert_eq!(h2.len(), 2);
        assert_eq!(h2.get(1).unwrap().content, "line1\nline2\twith tab \\ slash");
        assert_eq!(h2.get(0).unwrap().kind, KIND_IMAGE);
    }

    #[test]
    fn remove_clear_touch() {
        let mut h = History::new();
        h.add(KIND_IMAGE, "/x.png", 1);
        h.add(KIND_TEXT, "t", 2);
        assert!(h.touch(1, 3));
        assert_eq!(h.get(0).unwrap().content, "/x.png");
        assert_eq!(h.remove(0), "/x.png");
        h.add(KIND_IMAGE, "/y.png", 4);
        assert_eq!(h.clear(), "/y.png");
        assert!(h.is_empty());
    }
}

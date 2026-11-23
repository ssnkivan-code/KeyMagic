//! Finds one-time codes (OTP / verification codes) in message text.
//!
//! Rules:
//! * The message must mention a code-like word (code, OTP, PIN, verification…),
//!   so mobile-money receipts, balances and transaction IDs are ignored.
//! * A code is 4–8 digits, optionally split once by `-` or a space ("123-456"),
//!   optionally with a letter prefix like Google's "G-123456".
//! * Candidates next to money words (UGX, Shs, $, balance…) or that look like
//!   phone numbers, dates or times are skipped; the one closest to a keyword wins.

const KEYWORDS: &[&str] = &[
    "otp", "code", "codes", "verification", "verify", "pin", "passcode", "password",
    "one-time", "one time", "login", "log in", "sign in", "sign-in", "2fa", "auth",
    "authentication", "confirm", "confirmation", "security", "token", "access",
];

const MONEY_WORDS: &[&str] = &[
    "ugx", "ush", "shs", "kes", "tzs", "rwf", "usd", "eur", "gbp", "$", "€", "£",
    "bal", "balance", "amount", "amt", "fee", "charge", "tid", "txn", "trans",
    "transaction", "ref", "reference", "a/c", "acc", "account", "id:", "receipt",
];

#[derive(Debug)]
struct Candidate {
    code: String,
    start: usize, // char index in the lowered text
    end: usize,
}

/// Returns the most likely code, or `None`.
pub fn extract(text: &str) -> Option<String> {
    let lower: Vec<char> = text.to_lowercase().chars().collect();
    let lower_s: String = lower.iter().collect();

    let keyword_pos = keyword_positions(&lower_s);
    if keyword_pos.is_empty() {
        return None;
    }

    let mut best: Option<(i64, Candidate)> = None;
    for cand in candidates(&lower) {
        if looks_like_money_or_id(&lower, &cand) {
            continue;
        }
        let digits = cand.code.len();
        // Distance (in chars) to the nearest keyword.
        let dist = keyword_pos
            .iter()
            .map(|&(ks, ke)| {
                if ke <= cand.start {
                    (cand.start - ke) as i64
                } else if ks >= cand.end {
                    (ks - cand.end) as i64
                } else {
                    0
                }
            })
            .min()
            .unwrap_or(i64::MAX);
        if dist > 80 {
            continue;
        }
        // 4-digit numbers that look like years need a keyword right next to them.
        if digits == 4 && looks_like_year(&cand.code) && dist > 12 {
            continue;
        }
        let mut score = 1000 - dist * 5;
        if digits == 6 {
            score += 40; // by far the most common OTP length
        }
        if best.as_ref().is_none_or(|(s, _)| score > *s) {
            best = Some((score, cand));
        }
    }
    best.map(|(_, c)| c.code)
}

fn keyword_positions(lower: &str) -> Vec<(usize, usize)> {
    let chars: Vec<char> = lower.chars().collect();
    let mut out = Vec::new();
    for kw in KEYWORDS {
        let kw_chars: Vec<char> = kw.chars().collect();
        let n = kw_chars.len();
        if n > chars.len() {
            continue;
        }
        for i in 0..=chars.len() - n {
            if chars[i..i + n] == kw_chars[..] {
                let before_ok = i == 0 || !chars[i - 1].is_alphanumeric();
                let after_ok = i + n == chars.len() || !chars[i + n].is_alphabetic();
                if before_ok && after_ok {
                    out.push((i, i + n));
                }
            }
        }
    }
    out
}

fn candidates(chars: &[char]) -> Vec<Candidate> {
    let mut out = Vec::new();
    let n = chars.len();
    let mut i = 0;
    while i < n {
        if !chars[i].is_ascii_digit() || (i > 0 && is_numberish(chars[i - 1])) {
            i += 1;
            continue;
        }
        // first group of digits
        let start = i;
        let mut j = i;
        while j < n && chars[j].is_ascii_digit() {
            j += 1;
        }
        let mut code: String = chars[start..j].iter().collect();
        let mut end = j;
        // optional single separator followed by a second group ("123-456", "123 456")
        if j + 1 < n && (chars[j] == '-' || chars[j] == ' ') && chars[j + 1].is_ascii_digit() {
            let mut k = j + 1;
            while k < n && chars[k].is_ascii_digit() {
                k += 1;
            }
            let second: String = chars[j + 1..k].iter().collect();
            let joined = code.len() + second.len();
            if (2..=4).contains(&code.len()) && (2..=4).contains(&second.len()) && (6..=8).contains(&joined) {
                code.push_str(&second);
                end = k;
            }
        }
        let trailing_ok = end == n || !is_numberish(chars[end]) || is_sentence_end(chars, end);
        if (4..=8).contains(&code.len()) && trailing_ok {
            out.push(Candidate { code, start, end });
        }
        i = end.max(i + 1);
    }
    out
}

/// Characters that make a digit run part of a bigger number (1,000 / 12.50 / 0772…).
fn is_numberish(c: char) -> bool {
    c.is_ascii_digit() || c == ',' || c == '.' || c == ':' || c == '/' || c == '+'
}

/// "…code is 1234." – a full stop at the end of a sentence is fine.
fn is_sentence_end(chars: &[char], i: usize) -> bool {
    chars[i] == '.' && (i + 1 == chars.len() || chars[i + 1].is_whitespace())
}

fn looks_like_year(code: &str) -> bool {
    code.starts_with("19") || code.starts_with("20")
}

fn looks_like_money_or_id(chars: &[char], c: &Candidate) -> bool {
    // Look at the ~14 characters before the number.
    let from = c.start.saturating_sub(14);
    let before: String = chars[from..c.start].iter().collect();
    let before = before.trim_end();
    if MONEY_WORDS.iter().any(|w| before.ends_with(w) || before.ends_with(&format!("{w}:")) || before.ends_with(&format!("{w}.")))
    {
        return true;
    }
    // …and a few after it ("5000 UGX", "1234 shs").
    let to = (c.end + 6).min(chars.len());
    let after: String = chars[c.end..to].iter().collect();
    let after = after.trim_start();
    if ["ugx", "shs", "ush", "kes", "usd", "/="].iter().any(|w| after.starts_with(w)) {
        return true;
    }
    // A decimal or thousands separator just before ("UGX 12,345" handled above; "1.2345").
    if c.start > 0 && matches!(chars[c.start - 1], ',' | '.') {
        return true;
    }
    false
}

#[cfg(test)]
mod tests {
    use super::extract;

    fn otp(s: &str) -> Option<String> {
        extract(s)
    }

    #[test]
    fn common_formats() {
        assert_eq!(otp("Your WhatsApp code: 123-456. Don't share this code."), Some("123456".into()));
        assert_eq!(otp("G-482913 is your Google verification code."), Some("482913".into()));
        assert_eq!(otp("Use 8812 as your OTP for login. Do not share"), Some("8812".into()));
        assert_eq!(otp("<#> 552901 is your Instagram code. abcDEF123"), Some("552901".into()));
        assert_eq!(otp("Your Airtel Money PIN reset code is 7781."), Some("7781".into()));
        assert_eq!(otp("Telegram code: 64012"), Some("64012".into()));
        assert_eq!(otp("Your verification code is 845 221"), Some("845221".into()));
    }

    #[test]
    fn ignores_money_and_ids() {
        assert_eq!(
            otp("You have received UGX 50,000 from JOHN 0772123456. Balance: UGX 120,500. TID 12345678"),
            None
        );
        assert_eq!(otp("Payment of 25000 UGX received. Ref 99887766"), None);
        assert_eq!(otp("Meeting at 10:30 on 12/05/2026, see you"), None);
        assert_eq!(otp("Call me on 0772 123 456 please"), None);
    }

    #[test]
    fn picks_code_not_money_when_both() {
        assert_eq!(
            otp("Withdraw UGX 200,000. Your one-time PIN is 4471. Balance UGX 1,234,500"),
            Some("4471".into())
        );
    }

    #[test]
    fn year_needs_keyword_next_to_it() {
        assert_eq!(otp("Code of conduct updated in 2024 for all staff members of the hospital"), None);
        assert_eq!(otp("Your code is 2024"), Some("2024".into()));
    }
}

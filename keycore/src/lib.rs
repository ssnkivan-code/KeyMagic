//! Keyboard engine: layouts, shift / caps-lock state and page switching.
//!
//! The Android side asks this engine *what* to draw (key kind, label, width)
//! and tells it *which* key was pressed. The engine answers with an action:
//! a Unicode code point to type, or one of the `ACT_*` codes below.

mod jni;
pub mod clipboard;
pub mod otp;
pub mod predict;

/// Nothing to send to the app (e.g. shift or page change) – just redraw.
pub const ACT_NONE: i32 = -1;
/// Delete one character before the cursor (or the selection).
pub const ACT_DELETE: i32 = -2;
/// Enter / editor action (Go, Search, Send…).
pub const ACT_ENTER: i32 = -3;
/// Open the emoji panel.
pub const ACT_EMOJI: i32 = -4;

/// Key kinds reported to Android (`key_kind`).
pub const KIND_CHAR: i32 = 0;
pub const KIND_SHIFT: i32 = 1;
pub const KIND_BACKSPACE: i32 = 2;
pub const KIND_ENTER: i32 = 3;
pub const KIND_SPACE: i32 = 4;
pub const KIND_TO_SYMBOLS: i32 = 5;
pub const KIND_TO_MORE: i32 = 6;
pub const KIND_TO_LETTERS: i32 = 7;
pub const KIND_EMOJI: i32 = 8;

/// Two shift taps closer than this lock caps.
const DOUBLE_TAP_MS: i64 = 350;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Key {
    Char(char),
    Shift,
    Backspace,
    Enter,
    Space,
    ToSymbols,
    ToMore,
    ToLetters,
    Emoji,
}

impl Key {
    /// Width in tenths of a standard key.
    fn width(self) -> i32 {
        match self {
            Key::Char(_) | Key::Emoji => 10,
            Key::Space => 50,
            _ => 15,
        }
    }

    fn kind(self) -> i32 {
        match self {
            Key::Char(_) => KIND_CHAR,
            Key::Shift => KIND_SHIFT,
            Key::Backspace => KIND_BACKSPACE,
            Key::Enter => KIND_ENTER,
            Key::Space => KIND_SPACE,
            Key::ToSymbols => KIND_TO_SYMBOLS,
            Key::ToMore => KIND_TO_MORE,
            Key::ToLetters => KIND_TO_LETTERS,
            Key::Emoji => KIND_EMOJI,
        }
    }
}

use Key::*;

const fn c(ch: char) -> Key {
    Char(ch)
}

const LETTERS: &[&[Key]] = &[
    &[c('q'), c('w'), c('e'), c('r'), c('t'), c('y'), c('u'), c('i'), c('o'), c('p')],
    &[c('a'), c('s'), c('d'), c('f'), c('g'), c('h'), c('j'), c('k'), c('l')],
    &[Shift, c('z'), c('x'), c('c'), c('v'), c('b'), c('n'), c('m'), Backspace],
    &[ToSymbols, c(','), Space, c('.'), Enter],
];

const NUMBER_ROW: &[Key] =
    &[c('1'), c('2'), c('3'), c('4'), c('5'), c('6'), c('7'), c('8'), c('9'), c('0')];

const SYMBOLS: &[&[Key]] = &[
    &[c('1'), c('2'), c('3'), c('4'), c('5'), c('6'), c('7'), c('8'), c('9'), c('0')],
    &[c('@'), c('#'), c('$'), c('_'), c('&'), c('-'), c('+'), c('('), c(')'), c('/')],
    &[ToMore, c('*'), c('"'), c('\''), c(':'), c(';'), c('!'), c('?'), Backspace],
    &[ToLetters, c(','), Space, c('.'), Enter],
];

const MORE_SYMBOLS: &[&[Key]] = &[
    &[c('~'), c('`'), c('|'), c('•'), c('√'), c('π'), c('÷'), c('×'), c('¶'), c('∆')],
    &[c('£'), c('€'), c('¥'), c('^'), c('°'), c('='), c('{'), c('}'), c('['), c(']')],
    &[ToSymbols, c('\\'), c('<'), c('>'), c('©'), c('®'), c('™'), c('✓'), Backspace],
    &[ToLetters, c(','), Space, c('.'), Enter],
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Page {
    Letters = 0,
    Symbols = 1,
    MoreSymbols = 2,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ShiftState {
    Off = 0,
    Once = 1,
    Locked = 2,
}

pub struct Engine {
    page: Page,
    shift: ShiftState,
    last_shift_ms: i64,
    number_row: bool,
}

impl Default for Engine {
    fn default() -> Self {
        Self::new()
    }
}

impl Engine {
    pub fn new() -> Self {
        Engine { page: Page::Letters, shift: ShiftState::Off, last_shift_ms: i64::MIN / 2, number_row: false }
    }

    /// Back to the letters page with shift off. Keeps the number-row setting.
    pub fn reset(&mut self) {
        let number_row = self.number_row;
        *self = Engine::new();
        self.number_row = number_row;
    }

    /// Show a 1–0 row above the letters.
    pub fn set_number_row(&mut self, on: bool) {
        self.number_row = on;
    }

    fn has_extra_row(&self) -> bool {
        self.number_row && self.page == Page::Letters
    }

    fn rows(&self) -> &'static [&'static [Key]] {
        match self.page {
            Page::Letters => LETTERS,
            Page::Symbols => SYMBOLS,
            Page::MoreSymbols => MORE_SYMBOLS,
        }
    }

    fn row(&self, row: i32) -> Option<&'static [Key]> {
        let mut r = usize::try_from(row).ok()?;
        if self.has_extra_row() {
            if r == 0 {
                return Some(NUMBER_ROW);
            }
            r -= 1;
        }
        self.rows().get(r).copied()
    }

    fn key(&self, row: i32, col: i32) -> Option<Key> {
        self.row(row)?.get(usize::try_from(col).ok()?).copied()
    }

    fn shifted(&self) -> bool {
        self.page == Page::Letters && self.shift != ShiftState::Off
    }

    // ---- queries used for drawing -------------------------------------

    pub fn page(&self) -> Page {
        self.page
    }

    pub fn shift_state(&self) -> ShiftState {
        self.shift
    }

    pub fn row_count(&self) -> i32 {
        self.rows().len() as i32 + i32::from(self.has_extra_row())
    }

    /// Rows on the letters page (the tallest page) – used to keep height constant.
    pub fn max_row_count(&self) -> i32 {
        LETTERS.len() as i32 + i32::from(self.number_row)
    }

    pub fn key_count(&self, row: i32) -> i32 {
        self.row(row).map_or(0, |r| r.len() as i32)
    }

    pub fn key_kind(&self, row: i32, col: i32) -> i32 {
        self.key(row, col).map_or(-1, Key::kind)
    }

    pub fn key_width(&self, row: i32, col: i32) -> i32 {
        self.key(row, col).map_or(0, Key::width)
    }

    /// Code point to show on a character key (already upper-cased when shifted),
    /// or 0 for special keys.
    pub fn key_label(&self, row: i32, col: i32) -> i32 {
        match self.key(row, col) {
            Some(Char(ch)) => self.apply_shift(ch) as i32,
            _ => 0,
        }
    }

    fn apply_shift(&self, ch: char) -> char {
        if self.shifted() {
            // Single-char upper-casing is all we need for these layouts.
            let mut up = ch.to_uppercase();
            match (up.next(), up.next()) {
                (Some(u), None) => u,
                _ => ch,
            }
        } else {
            ch
        }
    }

    // ---- state changes ------------------------------------------------

    /// Turn on / off one-shot shift for auto-capitalisation (start of sentence).
    /// Never overrides caps lock.
    pub fn set_auto_shift(&mut self, on: bool) {
        match (on, self.shift) {
            (true, ShiftState::Off) => self.shift = ShiftState::Once,
            (false, ShiftState::Once) => self.shift = ShiftState::Off,
            _ => {}
        }
    }

    pub fn set_page(&mut self, page: i32) {
        self.page = match page {
            1 => Page::Symbols,
            2 => Page::MoreSymbols,
            _ => Page::Letters,
        };
    }

    /// Handle a key press. Returns a code point to type, or an `ACT_*` code.
    pub fn press(&mut self, row: i32, col: i32, now_ms: i64) -> i32 {
        let Some(key) = self.key(row, col) else {
            return ACT_NONE;
        };
        match key {
            Char(ch) => {
                let out = self.apply_shift(ch);
                if self.shift == ShiftState::Once {
                    self.shift = ShiftState::Off;
                }
                out as i32
            }
            Space => ' ' as i32,
            Backspace => ACT_DELETE,
            Enter => ACT_ENTER,
            Shift => {
                let quick = now_ms.saturating_sub(self.last_shift_ms) < DOUBLE_TAP_MS;
                self.shift = match self.shift {
                    ShiftState::Off => ShiftState::Once,
                    ShiftState::Once if quick => ShiftState::Locked,
                    ShiftState::Once => ShiftState::Off,
                    ShiftState::Locked => ShiftState::Off,
                };
                self.last_shift_ms = now_ms;
                ACT_NONE
            }
            ToSymbols => {
                self.page = Page::Symbols;
                ACT_NONE
            }
            ToMore => {
                self.page = Page::MoreSymbols;
                ACT_NONE
            }
            ToLetters => {
                self.page = Page::Letters;
                ACT_NONE
            }
            // The Kotlin side opens the emoji panel.
            Emoji => ACT_EMOJI,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn find(e: &Engine, kind: i32) -> (i32, i32) {
        for r in 0..e.row_count() {
            for k in 0..e.key_count(r) {
                if e.key_kind(r, k) == kind {
                    return (r, k);
                }
            }
        }
        panic!("kind {kind} not on page {:?}", e.page());
    }

    fn find_char(e: &Engine, ch: char) -> (i32, i32) {
        for r in 0..e.row_count() {
            for k in 0..e.key_count(r) {
                if e.key_kind(r, k) == KIND_CHAR
                    && char::from_u32(e.key_label(r, k) as u32).unwrap().to_ascii_lowercase() == ch
                {
                    return (r, k);
                }
            }
        }
        panic!("char {ch} not found");
    }

    #[test]
    fn types_lowercase_by_default() {
        let mut e = Engine::new();
        let (r, k) = find_char(&e, 'q');
        assert_eq!(e.press(r, k, 0), 'q' as i32);
    }

    #[test]
    fn shift_once_then_off() {
        let mut e = Engine::new();
        let (sr, sk) = find(&e, KIND_SHIFT);
        e.press(sr, sk, 1000);
        assert_eq!(e.shift_state(), ShiftState::Once);
        let (r, k) = find_char(&e, 'a');
        assert_eq!(e.key_label(r, k), 'A' as i32);
        assert_eq!(e.press(r, k, 1100), 'A' as i32);
        assert_eq!(e.press(r, k, 1200), 'a' as i32);
    }

    #[test]
    fn double_tap_locks_caps() {
        let mut e = Engine::new();
        let (sr, sk) = find(&e, KIND_SHIFT);
        e.press(sr, sk, 1000);
        e.press(sr, sk, 1200);
        assert_eq!(e.shift_state(), ShiftState::Locked);
        let (r, k) = find_char(&e, 'b');
        assert_eq!(e.press(r, k, 1300), 'B' as i32);
        assert_eq!(e.press(r, k, 1400), 'B' as i32);
        e.press(sr, sk, 5000);
        assert_eq!(e.shift_state(), ShiftState::Off);
    }

    #[test]
    fn slow_second_tap_turns_shift_off() {
        let mut e = Engine::new();
        let (sr, sk) = find(&e, KIND_SHIFT);
        e.press(sr, sk, 1000);
        e.press(sr, sk, 3000);
        assert_eq!(e.shift_state(), ShiftState::Off);
    }

    #[test]
    fn auto_shift_does_not_break_caps_lock() {
        let mut e = Engine::new();
        e.set_auto_shift(true);
        assert_eq!(e.shift_state(), ShiftState::Once);
        e.set_auto_shift(false);
        assert_eq!(e.shift_state(), ShiftState::Off);
        let (sr, sk) = find(&e, KIND_SHIFT);
        e.press(sr, sk, 0);
        e.press(sr, sk, 100);
        e.set_auto_shift(false);
        assert_eq!(e.shift_state(), ShiftState::Locked);
    }

    #[test]
    fn page_switching_and_special_actions() {
        let mut e = Engine::new();
        let (r, k) = find(&e, KIND_TO_SYMBOLS);
        assert_eq!(e.press(r, k, 0), ACT_NONE);
        assert_eq!(e.page(), Page::Symbols);
        let (r, k) = find_char(&e, '1');
        assert_eq!(e.press(r, k, 0), '1' as i32);
        let (r, k) = find(&e, KIND_TO_MORE);
        e.press(r, k, 0);
        assert_eq!(e.page(), Page::MoreSymbols);
        let (r, k) = find(&e, KIND_TO_LETTERS);
        e.press(r, k, 0);
        assert_eq!(e.page(), Page::Letters);
        let (r, k) = find(&e, KIND_BACKSPACE);
        assert_eq!(e.press(r, k, 0), ACT_DELETE);
        let (r, k) = find(&e, KIND_ENTER);
        assert_eq!(e.press(r, k, 0), ACT_ENTER);
        let (r, k) = find(&e, KIND_SPACE);
        assert_eq!(e.press(r, k, 0), ' ' as i32);
    }

    #[test]
    fn symbols_are_not_shifted() {
        let mut e = Engine::new();
        e.set_auto_shift(true);
        e.set_page(1);
        let (r, k) = find_char(&e, '@');
        assert_eq!(e.press(r, k, 0), '@' as i32);
    }

    #[test]
    fn number_row_only_on_letters() {
        let mut e = Engine::new();
        assert_eq!(e.row_count(), 4);
        e.set_number_row(true);
        assert_eq!(e.row_count(), 5);
        assert_eq!(e.max_row_count(), 5);
        assert_eq!(e.press(0, 0, 0), '1' as i32);
        assert_eq!(e.press(1, 0, 0), 'q' as i32);
        e.set_page(1);
        assert_eq!(e.row_count(), 4);
        e.reset();
        assert_eq!(e.row_count(), 5, "reset keeps the setting");
    }

    #[test]
    fn bottom_row_matches_samsung_style() {
        // [!#1] [,] [space] [.] [enter]  – emoji lives in the toolbar
        let e = Engine::new();
        let kinds: Vec<i32> = (0..e.key_count(3)).map(|k| e.key_kind(3, k)).collect();
        assert_eq!(kinds, vec![KIND_TO_SYMBOLS, KIND_CHAR, KIND_SPACE, KIND_CHAR, KIND_ENTER]);
        let total: i32 = (0..e.key_count(3)).map(|k| e.key_width(3, k)).sum();
        assert_eq!(total, 100);
    }

    #[test]
    fn out_of_range_is_safe() {
        let mut e = Engine::new();
        assert_eq!(e.press(-1, 0, 0), ACT_NONE);
        assert_eq!(e.press(9, 9, 0), ACT_NONE);
        assert_eq!(e.key_count(42), 0);
        assert_eq!(e.key_kind(0, 99), -1);
    }

    #[test]
    fn rows_fit_within_ten_keys() {
        for page in 0..3 {
            let mut e = Engine::new();
            e.set_page(page);
            for r in 0..e.row_count() {
                let total: i32 = (0..e.key_count(r)).map(|k| e.key_width(r, k)).sum();
                assert!(total <= 100, "page {page} row {r} is {total} wide");
            }
        }
    }
}

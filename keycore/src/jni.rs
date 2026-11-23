//! JNI bridge for `com.ssonkoict.keyboard.NativeCore` (a Kotlin `object` with
//! `@JvmStatic external` functions).
//!
//! No `jni` crate: the few JNIEnv functions we need (UTF-16 string in/out) are
//! called straight from the JNI function table. Indices are fixed by the JNI spec
//! (see `jni.h`): NewString = 163, GetStringLength = 164, GetStringChars = 165,
//! ReleaseStringChars = 166. UTF-16 is used (not "modified UTF-8") so emojis
//! survive the trip.
//!
//! One `Core` (keyboard engine + predictor + clipboard history) lives behind a
//! `Mutex` on the Rust heap; Kotlin holds the pointer as a `Long` handle.

use crate::clipboard::History;
use crate::predict::Predictor;
use crate::{otp, Engine};
use std::ffi::c_void;
use std::sync::Mutex;

type JEnv = *mut c_void;
type JClass = *mut c_void;
type JString = *mut c_void;
type JBoolean = u8;

pub struct Core {
    pub engine: Engine,
    pub predictor: Predictor,
    pub clip: History,
}

// ------------------------------------------------------------------ strings

unsafe fn env_fn(env: JEnv, index: usize) -> *const c_void {
    // JNIEnv* points at a pointer to the function table.
    let table = *(env as *const *const *const c_void);
    *table.add(index)
}

unsafe fn from_jstring(env: JEnv, s: JString) -> String {
    if env.is_null() || s.is_null() {
        return String::new();
    }
    type GetLen = unsafe extern "system" fn(JEnv, JString) -> i32;
    type GetChars = unsafe extern "system" fn(JEnv, JString, *mut JBoolean) -> *const u16;
    type Release = unsafe extern "system" fn(JEnv, JString, *const u16);
    let get_len: GetLen = std::mem::transmute(env_fn(env, 164));
    let get_chars: GetChars = std::mem::transmute(env_fn(env, 165));
    let release: Release = std::mem::transmute(env_fn(env, 166));

    let len = get_len(env, s);
    let ptr = get_chars(env, s, std::ptr::null_mut());
    if ptr.is_null() || len <= 0 {
        if !ptr.is_null() {
            release(env, s, ptr);
        }
        return String::new();
    }
    let out = String::from_utf16_lossy(std::slice::from_raw_parts(ptr, len as usize));
    release(env, s, ptr);
    out
}

unsafe fn to_jstring(env: JEnv, s: &str) -> JString {
    type NewString = unsafe extern "system" fn(JEnv, *const u16, i32) -> JString;
    let new_string: NewString = std::mem::transmute(env_fn(env, 163));
    let utf16: Vec<u16> = s.encode_utf16().collect();
    new_string(env, utf16.as_ptr(), utf16.len() as i32)
}

// ------------------------------------------------------------------- handle

fn with_core<R>(handle: i64, default: R, f: impl FnOnce(&mut Core) -> R) -> R {
    if handle == 0 {
        return default;
    }
    // SAFETY: handle was produced by `create` and is only freed by `destroy`.
    let m = unsafe { &*(handle as *const Mutex<Core>) };
    let mut guard = m.lock().unwrap_or_else(|p| p.into_inner());
    f(&mut guard)
}

macro_rules! jni_fn {
    ($name:ident ( $env:ident $(, $arg:ident : $ty:ty)* ) -> $ret:ty $body:block) => {
        #[no_mangle]
        #[allow(unused_variables, clippy::missing_safety_doc)]
        pub unsafe extern "system" fn $name($env: JEnv, _class: JClass $(, $arg: $ty)*) -> $ret $body
    };
}

// ------------------------------------------------------------------- core

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_create(env) -> i64 {
    let core = Core { engine: Engine::new(), predictor: Predictor::new(), clip: History::new() };
    Box::into_raw(Box::new(Mutex::new(core))) as i64
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_destroy(env, h: i64) -> () {
    if h != 0 {
        drop(Box::from_raw(h as *mut Mutex<Core>));
    }
});

// ------------------------------------------------------------------- engine

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_reset(env, h: i64) -> () {
    with_core(h, (), |c| c.engine.reset())
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_press(env, h: i64, row: i32, col: i32, now_ms: i64) -> i32 {
    with_core(h, crate::ACT_NONE, |c| c.engine.press(row, col, now_ms))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_rowCount(env, h: i64) -> i32 {
    with_core(h, 0, |c| c.engine.row_count())
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_maxRowCount(env, h: i64) -> i32 {
    with_core(h, 0, |c| c.engine.max_row_count())
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_keyCount(env, h: i64, row: i32) -> i32 {
    with_core(h, 0, |c| c.engine.key_count(row))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_keyKind(env, h: i64, row: i32, col: i32) -> i32 {
    with_core(h, -1, |c| c.engine.key_kind(row, col))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_keyLabel(env, h: i64, row: i32, col: i32) -> i32 {
    with_core(h, 0, |c| c.engine.key_label(row, col))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_keyWidth(env, h: i64, row: i32, col: i32) -> i32 {
    with_core(h, 0, |c| c.engine.key_width(row, col))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_shiftState(env, h: i64) -> i32 {
    with_core(h, 0, |c| c.engine.shift_state() as i32)
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_page(env, h: i64) -> i32 {
    with_core(h, 0, |c| c.engine.page() as i32)
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_setPage(env, h: i64, page: i32) -> () {
    with_core(h, (), |c| c.engine.set_page(page))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_setAutoShift(env, h: i64, on: JBoolean) -> () {
    with_core(h, (), |c| c.engine.set_auto_shift(on != 0))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_setNumberRow(env, h: i64, on: JBoolean) -> () {
    with_core(h, (), |c| c.engine.set_number_row(on != 0))
});

// ---------------------------------------------------------------- predictor

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_loadDictionary(env, h: i64, data: JString) -> i32 {
    // Parse outside the lock so typing isn't blocked while the word list loads.
    let dict = crate::predict::Dictionary::parse(&from_jstring(env, data));
    let n = dict.len() as i32;
    with_core(h, 0, |c| {
        c.predictor.install_dictionary(dict);
        n
    })
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_loadEmoji(env, h: i64, data: JString) -> () {
    let data = from_jstring(env, data);
    with_core(h, (), |c| c.predictor.load_emoji(&data))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_suggest(env, h: i64, before: JString, max: i32) -> JString {
    let before = from_jstring(env, before);
    let out = with_core(h, Vec::new(), |c| c.predictor.suggest(&before, max.max(0) as usize));
    to_jstring(env, &out.join("\n"))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_suggestEmoji(env, h: i64, before: JString, max: i32) -> JString {
    let before = from_jstring(env, before);
    let out = with_core(h, Vec::new(), |c| c.predictor.suggest_emoji(&before, max.max(0) as usize));
    to_jstring(env, &out.join("\n"))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_learn(env, h: i64, before: JString) -> () {
    let before = from_jstring(env, before);
    with_core(h, (), |c| c.predictor.learn(&before))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_forget(env, h: i64, word: JString) -> () {
    let word = from_jstring(env, word);
    with_core(h, (), |c| c.predictor.forget(&word))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clearUser(env, h: i64) -> () {
    with_core(h, (), |c| c.predictor.clear_user())
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_recordEmoji(env, h: i64, emoji: JString) -> () {
    let emoji = from_jstring(env, emoji);
    with_core(h, (), |c| c.predictor.record_emoji(&emoji))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_frequentEmojis(env, h: i64, max: i32) -> JString {
    let out = with_core(h, Vec::new(), |c| c.predictor.frequent_emojis(max.max(0) as usize));
    to_jstring(env, &out.join("\n"))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_exportUser(env, h: i64) -> JString {
    let out = with_core(h, String::new(), |c| c.predictor.export_user());
    to_jstring(env, &out)
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_importUser(env, h: i64, data: JString) -> () {
    let data = from_jstring(env, data);
    with_core(h, (), |c| c.predictor.import_user(&data))
});

// ---------------------------------------------------------------- clipboard

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clipAdd(env, h: i64, kind: i32, content: JString, time_ms: i64) -> JString {
    let content = from_jstring(env, content);
    let freed = with_core(h, String::new(), |c| c.clip.add(kind, &content, time_ms));
    to_jstring(env, &freed)
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clipCount(env, h: i64) -> i32 {
    with_core(h, 0, |c| c.clip.len() as i32)
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clipKind(env, h: i64, i: i32) -> i32 {
    with_core(h, -1, |c| usize::try_from(i).ok().and_then(|i| c.clip.get(i)).map_or(-1, |it| it.kind))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clipContent(env, h: i64, i: i32) -> JString {
    let s = with_core(h, String::new(), |c| {
        usize::try_from(i).ok().and_then(|i| c.clip.get(i)).map_or_else(String::new, |it| it.content.clone())
    });
    to_jstring(env, &s)
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clipTime(env, h: i64, i: i32) -> i64 {
    with_core(h, 0, |c| usize::try_from(i).ok().and_then(|i| c.clip.get(i)).map_or(0, |it| it.time_ms))
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clipTouch(env, h: i64, i: i32, time_ms: i64) -> JBoolean {
    with_core(h, 0, |c| usize::try_from(i).is_ok_and(|i| c.clip.touch(i, time_ms)) as JBoolean)
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clipRemove(env, h: i64, i: i32) -> JString {
    let s = with_core(h, String::new(), |c| usize::try_from(i).map_or_else(|_| String::new(), |i| c.clip.remove(i)));
    to_jstring(env, &s)
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clipClear(env, h: i64) -> JString {
    let s = with_core(h, String::new(), |c| c.clip.clear());
    to_jstring(env, &s)
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clipExport(env, h: i64) -> JString {
    let s = with_core(h, String::new(), |c| c.clip.export());
    to_jstring(env, &s)
});

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_clipImport(env, h: i64, data: JString) -> () {
    let data = from_jstring(env, data);
    with_core(h, (), |c| c.clip.import(&data))
});

// ---------------------------------------------------------------------- otp

jni_fn!(Java_com_ssonkoict_keyboard_NativeCore_findOtp(env, text: JString) -> JString {
    let text = from_jstring(env, text);
    to_jstring(env, &otp::extract(&text).unwrap_or_default())
});

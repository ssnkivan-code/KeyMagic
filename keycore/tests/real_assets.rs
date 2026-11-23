//! Runs the predictor against the real app assets (word list + emoji data).
use keycore::predict::Predictor;
use std::time::Instant;

fn load() -> Option<Predictor> {
    let words = std::fs::read_to_string("../app/src/main/assets/words.txt").ok()?;
    let emoji = std::fs::read_to_string("../app/src/main/assets/emoji.tsv").ok()?;
    let mut p = Predictor::new();
    let t = Instant::now();
    p.load_dictionary(&words);
    p.load_emoji(&emoji);
    println!("loaded {} words in {:?}", p.dictionary_size(), t.elapsed());
    Some(p)
}

#[test]
fn real_suggestions_look_sensible() {
    let Some(p) = load() else { return };
    for q in ["I want to go th", "hel", "Good mor", "see you tomor", "the hospial", "Kamp", "webale", "I'm so happ", "I love", "pizza "] {
        let t = Instant::now();
        println!("{q:>18} -> {:?} {:?}  ({:?})", p.suggest(q, 3), p.suggest_emoji(q, 2), t.elapsed());
    }
    assert_eq!(p.suggest("tomor", 1), vec!["tomorrow"]);
    assert!(p.suggest("the hospial", 3).contains(&"hospital".to_string()));
    assert_eq!(p.suggest("Kamp", 1), vec!["Kampala"]);
    assert!(!p.suggest_emoji("I love", 2).is_empty());
}

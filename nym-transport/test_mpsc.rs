use futures::channel::mpsc;
use futures::StreamExt;

fn main() {
    let (tx, rx) = mpsc::unbounded::<Vec<u8>>();
    // drop(rx);
    if let Err(e) = tx.unbounded_send(vec![1, 2, 3]) {
        println!("Error: {}", e);
    } else {
        println!("Success");
    }
}

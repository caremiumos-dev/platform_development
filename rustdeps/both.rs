//! Uses both bar and baz.

use bar::bar;
use baz::baz;

fn main() {
    println!("{}", bar() + baz());
}

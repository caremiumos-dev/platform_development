use std::collections::BTreeMap;

use chrono::{DateTime, Local};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize)]
struct UpdatesTried {
    crates: BTreeMap<String, CrateUpdate>,
}

#[derive(Serialize, Deserialize)]
struct CrateUpdate {
    name: String,
    version: String,
    date: DateTime<Local>,
    success: bool,
}

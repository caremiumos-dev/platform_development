use std::{
    collections::BTreeMap,
    fs::{read_to_string, write},
    path::Path,
};

use anyhow::Result;
use chrono::{DateTime, Days, Local};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Default)]
pub struct UpdatesTried {
    attempts: BTreeMap<String, CrateUpdate>,
}

#[derive(Serialize, Deserialize)]
struct CrateUpdate {
    name: String,
    version: String,
    time: DateTime<Local>,
    success: bool,
}

impl UpdatesTried {
    pub fn read() -> Result<Self> {
        let mut parsed: UpdatesTried = if Path::new("updates-tried.json").exists() {
            serde_json::from_str(read_to_string("updates-tried.json")?.as_str())?
        } else {
            UpdatesTried::default()
        };
        let now = chrono::Local::now();
        parsed.attempts.retain(|_, u| u.time.checked_add_days(Days::new(14)).unwrap_or(now) > now);
        Ok(parsed)
    }
    pub fn write(&self) -> Result<()> {
        let mut contents = serde_json::to_string_pretty(self)?;
        contents.push('\n');
        write("updates-tried.json", contents)?;
        Ok(())
    }
    pub fn contains(&self, name: &str, version: &str) -> bool {
        self.attempts.contains_key(&key(name, version))
    }
    pub fn record(&mut self, name: String, version: String, success: bool) -> Result<()> {
        self.attempts.insert(
            key(&name, &version),
            CrateUpdate { name, version, time: chrono::Local::now(), success },
        );
        self.write()
    }
}

fn key(name: &str, version: &str) -> String {
    format!("{name}-v{version}")
}

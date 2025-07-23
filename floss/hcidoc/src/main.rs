use clap::{Arg, ArgAction, Command};
use std::io::Write;

mod engine;
mod groups;
mod parser;

use crate::engine::RuleEngine;
use crate::groups::{collisions, connections, controllers, informational};
use crate::parser::{LogParser, Packet, SnoopOpcodes};

fn main() {
    let matches = Command::new("hcidoc")
        .version("0.1")
        .author("Abhishek Pandit-Subedi <abhishekpandit@google.com>")
        .about("Analyzes a linux HCI snoop log for specific behaviors and errors.")
        .arg(
            Arg::new("filename")
                .help("Path to the snoop log. If omitted, read from stdin instead."),
        )
        .arg(
            Arg::new("ignore-unknown")
                .long("ignore-unknown")
                .action(ArgAction::SetTrue)
                .help("Don't print warning for unknown opcodes"),
        )
        .arg(
            Arg::new("signals")
                .short('s')
                .long("signals")
                .action(ArgAction::SetTrue)
                .help("Report signals from active rules."),
        )
        .arg(
            Arg::new("signals-only")
                .long("signals-only")
                .action(ArgAction::SetTrue)
                .help("Only print signals from active rules, don't print other events."),
        )
        .arg(
            Arg::new("json")
                .long("json")
                .action(ArgAction::SetTrue)
                .help("Print summary in JSON format"),
        )
        .arg(
            Arg::new("json-only")
                .long("json-only")
                .action(ArgAction::SetTrue)
                .help("Only print JSON summary, don't print others."),
        )
        .get_matches();

    let filename = match matches.get_one::<String>("filename") {
        Some(f) => f,
        None => "",
    };

    let ignore_unknown_opcode = match matches.get_one::<bool>("ignore-unknown") {
        Some(v) => *v,
        None => false,
    };

    let mut report_signals = match matches.get_one::<bool>("signals") {
        Some(v) => *v,
        None => false,
    };

    let report_only_signals = match matches.get_one::<bool>("signals-only") {
        Some(v) => *v,
        None => false,
    };

    if report_only_signals {
        report_signals = true;
    }

    let json = match matches.get_one::<bool>("json") {
        Some(v) => *v,
        None => false,
    };

    let json_only = match matches.get_one::<bool>("json-only") {
        Some(v) => *v,
        None => false,
    };

    let parser = match LogParser::new(filename) {
        Ok(p) => p,
        Err(e) => {
            println!(
                "Failed to load parser on {}: {}",
                if filename.len() == 0 { "stdin" } else { filename },
                e
            );
            return;
        }
    };

    // Create engine with default rule groups.
    let mut engine = RuleEngine::new();
    engine.add_rule_group("Collisions".into(), collisions::get_collisions_group());
    engine.add_rule_group("Connections".into(), connections::get_connections_group());
    engine.add_rule_group("Controllers".into(), controllers::get_controllers_group());
    engine.add_rule_group("Informational".into(), informational::get_informational_group());

    // Decide where to write output.
    let mut writer: Box<dyn Write> = Box::new(std::io::stdout());

    for (pos, v) in parser.get_snoop_iterator().enumerate() {
        match Packet::try_from((pos, &*v)) {
            Ok(p) => engine.process(p),
            Err(e) => {
                if !ignore_unknown_opcode {
                    match v.opcode() {
                        SnoopOpcodes::Command | SnoopOpcodes::Event => {
                            eprintln!("#{}: {}", pos, e);
                        }
                        _ => (),
                    }
                }
            }
        }
    }

    if !report_only_signals && !json_only {
        engine.report(&mut writer);
    }
    if report_signals && !json_only {
        let _ = writeln!(&mut writer, "### Signals ###");
        engine.report_signals(&mut writer);
    }

    if json {
        engine.output_json(&mut writer);
    }
}

import json
from datetime import datetime
from pathlib import Path

# Load the JSON file
input_file = "elogs.entry.json"
output_dir = Path("partitioned_logs")
output_dir.mkdir(exist_ok=True)  # Ensure output directory exists

# Read and parse the JSON file
with open(input_file, "r") as f:
    entries = json.load(f)

# Group entries by date
entries_by_date = {}
for entry in entries:
    # Parse the 'eventAt' date field to extract the date
    event_date = entry.get("loggedAt", {}).get("$date")
    if event_date:
        date_key = datetime.fromisoformat(event_date.rstrip("Z")).strftime("%Y-%m-%d")
        entries_by_date.setdefault(date_key, []).append(entry)

# Write each group to a separate JSON file
for date_key, day_entries in entries_by_date.items():
    # Sort entries by 'eventAt' timestamp
    day_entries.sort(key=lambda x: x.get("loggedAt", {}).get("$date", ""))
    
    # Construct the output filename
    output_file = output_dir / f"elogs.entry.{date_key}.json"
    
    # Write to the file
    with open(output_file, "w") as f:
        json.dump(day_entries, f, indent=2)

print(f"Partitioned logs written to: {output_dir.resolve()}")

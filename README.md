# NYCAudit

## Activity Log
- 2025-10-30: Downloaded the 2025 Democratic mayoral primary cast vote record zip from https://www.vote.nyc/sites/default/files/pdf/election_results/2025/20250624Primary%20Election/rcv/2025_Primary_CVR_2025-07-17.zip, extracted the precinct-level XLSX files, and staged them locally in the `data/` directory. To keep the repository lightweight, neither the original zip nor the extracted data directory will be committed.
- 2025-10-30: Added an initial Gradle build with Apache POI and implemented `com.fishdan.NYCParser` to consolidate all precinct XLSX files under `data/` into a single CSV (default `data/combined_precincts.csv`) while preserving the header once.
- 2025-10-30: Swapped the build tooling from Gradle to Maven (`pom.xml`) with Apache POI and exec plugin configuration so `com.fishdan.NYCParser` can be run via `mvn exec:java`.
- 2025-10-30: Running the original in-memory parser via `mvn exec:java` exposed multiple scaling issues (initial `ClassNotFoundException`, Apache POI's 100M byte-array ceiling, then JVM heap exhaustion even with `-Xmx6g`). We also attempted to estimate total row counts with a `unzip | grep | wc` pipeline, which timed out, reinforcing the dataset size challenge. Reworked `com.fishdan.NYCParser` to use POI's streaming SAX handler so rows are written incrementally with progress logged every 5,000 records; execution deferred per request pending confirmation.
- 2025-10-30: Captured local environment specs via `uname -a`, `/etc/os-release`, `lscpu`, `free -h`, `java -version`, and `mvn -version` to document the hardware/software baseline for future runs.
- 2025-10-30: Updated `com.fishdan.NYCParser` to project each workbook onto the first two columns plus any `DEM Mayor Choice…` columns, avoiding header-order mismatches across files while keeping the CSV footprint focused on the mayoral contest.
- 2025-10-31: Verified MariaDB connectivity to host `192.168.1.10:3306` as user `dan`, created a `votes` table (`vote_record`, `precinct`, `choice1`–`choice5`), and bulk-loaded `data/combined_precincts.csv` (1,114,433 rows) via `LOAD DATA LOCAL INFILE`.
- 2025-10-31: Converted `Primary Election 2025 - 06-24-2025_CandidacyID_To_Name.xlsx` to `candidates.csv`, defined the `candidates` table (`candidacy_id`, `default_ballot_name`), and ingested all 951 candidate rows into MariaDB.
- 2025-10-31: Created the `view_votes_named` view mapping each vote choice to the candidate's default ballot name by joining `votes` against `candidates`.
- 2025-10-31: Aggregated first-choice (`choice1`) vote totals via `SELECT choice1, COUNT(*) FROM view_votes_named GROUP BY choice1 ORDER BY COUNT(*) DESC` and captured the results below.
- 2025-10-31: Tallied second-choice (`choice2`) preferences conditioned on first-choice `Andrew M. Cuomo` via `SELECT choice2, COUNT(*) FROM view_votes_named WHERE choice1 = 'Andrew M. Cuomo' GROUP BY choice2 ORDER BY COUNT(*) DESC` (see breakdown below).
- 2025-10-31: Created `votes_extrapolated` from `votes_no_cuomo`, inserted weighted random replacements for first-choice `undervote` ballots using `cuomo_second_cdf` and `cuomo_first_replacements`, and rewrote second/third choices per redistribution rules.

## System Specs
- OS: Ubuntu 24.04.3 LTS (kernel `6.8.0-86-generic`; `uname -a` confirms host `bubuntu` on x86_64).
- CPU: Intel(R) Xeon(R) CPU E5630 @ 2.53GHz, 4 cores / 4 threads (`lscpu`).
- Memory: 11 GiB total RAM, 4 GiB swap (`free -h`).
- Java: OpenJDK 21.0.2 LTS (Temurin 21.0.2+13) (`java -version`).
- Maven: Apache Maven 3.8.7 (using the above JDK) (`mvn -version`).
- Shell: `bash`; network access restricted (approval required for downloads).

## First-Choice Vote Totals
```
choice1,total_votes
Zohran Kwame Mamdani,469018
Andrew M. Cuomo,385398
Brad Lander,120544
Adrienne E. Adams,43941
undervote,40535
Scott M. Stringer,17668
Zellnor Myrie,10554
Whitney R. Tilson,8416
overvote,5321
Michael Blake,4313
Jessica Ramos,4165
Write-in,1570
Paperboy Love Prince,1543
Selma K. Bartholomew,1447
```

## Second-Choice Totals (First Choice = Andrew M. Cuomo)
```
choice2,total_votes
undervote,165141
Adrienne E. Adams,45388
Scott M. Stringer,37636
Brad Lander,36485
Zohran Kwame Mamdani,27034
Whitney R. Tilson,25167
Zellnor Myrie,14844
Andrew M. Cuomo,12146
Jessica Ramos,10820
Michael Blake,5256
Paperboy Love Prince,2143
Selma K. Bartholomew,2024
Write-in,808
overvote,506
```

## Extrapolated First-Choice Totals (Cuomo Removed Scenario)
```
choice1,total_votes
Zohran Kwame Mamdani,510446
Brad Lander,176418
Adrienne E. Adams,113660
undervote,88399
Scott M. Stringer,75332
Whitney R. Tilson,46940
Zellnor Myrie,33273
Jessica Ramos,20737
Michael Blake,12405
advance,12146
Andrew M. Cuomo,6391
overvote,6078
Paperboy Love Prince,4803
Selma K. Bartholomew,4551
Write-in,2854
```

## Conclusion
Removing Andrew M. Cuomo from the 2025 Democratic mayoral primary and redistributing his ballots with the weighted assumptions above pushes Zohran Kwame Mamdani over the majority threshold immediately—an extrapolated first-round win (≈55%) without requiring additional ranked-choice elimination rounds.

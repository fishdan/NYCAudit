# NYCAudit

## Activity Log
- 2025-10-30: Downloaded the 2025 Democratic mayoral primary cast vote record zip from https://www.vote.nyc/sites/default/files/pdf/election_results/2025/20250624Primary%20Election/rcv/2025_Primary_CVR_2025-07-17.zip, extracted the precinct-level XLSX files, and staged them locally in the `data/` directory. To keep the repository lightweight, neither the original zip nor the extracted data directory will be committed.
- 2025-10-30: Added an initial Gradle build with Apache POI and implemented `com.fishdan.NYCParser` to consolidate all precinct XLSX files under `data/` into a single CSV (default `data/combined_precincts.csv`) while preserving the header once.
- 2025-10-30: Swapped the build tooling from Gradle to Maven (`pom.xml`) with Apache POI and exec plugin configuration so `com.fishdan.NYCParser` can be run via `mvn exec:java`.
- 2025-10-30: Running the original in-memory parser via `mvn exec:java` exposed multiple scaling issues (initial `ClassNotFoundException`, Apache POI's 100M byte-array ceiling, then JVM heap exhaustion even with `-Xmx6g`). We also attempted to estimate total row counts with a `unzip | grep | wc` pipeline, which timed out, reinforcing the dataset size challenge. Reworked `com.fishdan.NYCParser` to use POI's streaming SAX handler so rows are written incrementally with progress logged every 5,000 records; execution deferred per request pending confirmation.
- 2025-10-30: Captured local environment specs via `uname -a`, `/etc/os-release`, `lscpu`, `free -h`, `java -version`, and `mvn -version` to document the hardware/software baseline for future runs.
- 2025-10-30: Updated `com.fishdan.NYCParser` to project each workbook onto the first two columns plus any `DEM Mayor Choice…` columns, avoiding header-order mismatches across files while keeping the CSV footprint focused on the mayoral contest.

## System Specs
- OS: Ubuntu 24.04.3 LTS (kernel `6.8.0-86-generic`; `uname -a` confirms host `bubuntu` on x86_64).
- CPU: Intel(R) Xeon(R) CPU E5630 @ 2.53GHz, 4 cores / 4 threads (`lscpu`).
- Memory: 11 GiB total RAM, 4 GiB swap (`free -h`).
- Java: OpenJDK 21.0.2 LTS (Temurin 21.0.2+13) (`java -version`).
- Maven: Apache Maven 3.8.7 (using the above JDK) (`mvn -version`).
- Shell: `bash`; network access restricted (approval required for downloads).

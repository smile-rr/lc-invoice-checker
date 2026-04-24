# JDK 21 Local Dev Setup (macOS)

This project targets **JDK 21 (LTS)** for `lc-checker-api` (Spring Boot 3.x + Spring AI).
JDK 21 was installed via **SDKMAN** alongside the user's existing JDKs. All prior JDKs
remain untouched under `/Library/Java/JavaVirtualMachines/`.

---

## What was installed

| Component | Version | Location |
|---|---|---|
| Homebrew `bash` (prereq) | 5.3.9 | `/opt/homebrew/bin/bash` |
| SDKMAN | 5.22.4 (native 0.7.32) | `~/.sdkman/` |
| Eclipse Temurin JDK 21 LTS | **21.0.10-tem** | `~/.sdkman/candidates/java/21.0.10-tem/` |

Verification:

```bash
$ java -version
openjdk version "21.0.10" 2026-01-20 LTS
OpenJDK Runtime Environment Temurin-21.0.10+7 (build 21.0.10+7-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.10+7 (build 21.0.10+7-LTS, mixed mode, sharing)

$ echo $JAVA_HOME
/Users/pc-rn/.sdkman/candidates/java/current
```

`JAVA_HOME` is a symlink managed by SDKMAN — it always points at whichever
Java candidate is currently active (`~/.sdkman/candidates/java/current`).

---

## How SDKMAN was installed

macOS ships Bash 3.2, but the SDKMAN installer requires Bash 4+.
Steps (already executed, recorded here for reproducibility):

```bash
# 1. Install modern bash (required by SDKMAN installer only)
brew install bash

# 2. Run SDKMAN installer under modern bash
curl -s "https://get.sdkman.io" | /opt/homebrew/bin/bash

# 3. SDKMAN appended init snippets to:
#    ~/.zshrc         (user's login shell)
#    ~/.bashrc
#    ~/.bash_profile
#
# Snippet appended:
#   export SDKMAN_DIR="$HOME/.sdkman"
#   [[ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]] && source "$HOME/.sdkman/bin/sdkman-init.sh"

# 4. Open new shell (or `source ~/.zshrc`), then:
sdk install java 21.0.10-tem
sdk default java 21.0.10-tem
```

---

## Switching JDKs

SDKMAN-managed JDKs are selected via `sdk use` / `sdk default`.
The user's pre-existing JDKs (Oracle 17, Oracle/Zulu 11, Zulu/Oracle 8) were
**not** imported into SDKMAN and remain managed by macOS at
`/Library/Java/JavaVirtualMachines/`.

### Switch within SDKMAN-managed JDKs

```bash
# Temporary switch (current shell only)
sdk use java 21.0.10-tem

# Persistent default (all new shells)
sdk default java 21.0.10-tem

# List installed candidates
sdk list java | grep installed

# Show current
sdk current java
```

### Switch to a macOS-managed (pre-existing) JDK

Bypass SDKMAN and point `JAVA_HOME` at a macOS JDK directly:

```bash
# JDK 17 (Oracle)
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"

# JDK 11 (Oracle or Zulu — pick by arch if needed)
export JAVA_HOME=$(/usr/libexec/java_home -v 11)

# JDK 8 (arm64 Zulu)
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8 -a arm64)

java -version  # verify
```

To return to SDKMAN control: open a new shell, or `source ~/.sdkman/bin/sdkman-init.sh`.

### List all JDKs on this machine (both SDKMAN + macOS)

```bash
# macOS-managed
/usr/libexec/java_home -V

# SDKMAN-managed
sdk list java | grep installed
```

---

## Project usage (Gradle)

`lc-checker-api` will declare `sourceCompatibility = 21` / `targetCompatibility = 21`
in `build.gradle`. Gradle will pick up whatever `JAVA_HOME` points to, so make sure
JDK 21 is active before running:

```bash
cd lc-checker-api
./gradlew build
```

If Gradle reports "Java version mismatch", run `sdk use java 21.0.10-tem` first.

Optionally add a Gradle toolchain spec to build.gradle to pin 21 regardless of active JDK:

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}
```

---

## Uninstalling (reversibility)

The install is fully reversible. Nothing outside `~/.sdkman/` and the shell profile
snippets was changed.

### Uninstall JDK 21 only (keep SDKMAN)

```bash
sdk uninstall java 21.0.10-tem
```

### Uninstall SDKMAN entirely

```bash
# 1. Remove SDKMAN directory (removes all SDKMAN-managed JDKs)
rm -rf ~/.sdkman

# 2. Remove init snippet from shell profiles:
#    Edit ~/.zshrc, ~/.bashrc, ~/.bash_profile — delete the 3-line block:
#      # THIS MUST BE AT THE END OF THE FILE FOR SDKMAN TO WORK!!!
#      export SDKMAN_DIR="$HOME/.sdkman"
#      [[ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]] && source "$HOME/.sdkman/bin/sdkman-init.sh"

# 3. Open a new shell. Pre-existing Oracle/Zulu JDKs at
#    /Library/Java/JavaVirtualMachines/ continue to work via /usr/libexec/java_home.
```

### Uninstall modern bash (if desired)

```bash
brew uninstall bash
```

macOS falls back to the system `/bin/bash` (3.2). This is only safe **after**
SDKMAN uninstall — SDKMAN's own scripts require Bash 4+.

---

## Troubleshooting

**`java -version` still shows JDK 17 in a new terminal.**
Ensure the SDKMAN snippet is at the **end** of `~/.zshrc`. Some tools
(asdf, nvm, etc.) overwrite `PATH` later. `tail ~/.zshrc` to confirm.

**`sdk: command not found`.**
`source ~/.sdkman/bin/sdkman-init.sh` in the current shell, or open a new one.

**Gradle builds with wrong JDK.**
Either export `JAVA_HOME` before `./gradlew`, or add the toolchain spec shown above.

**`compdef:153: _comps: assignment to invalid subscript range` warning.**
Harmless zsh completion quirk from SDKMAN when sourced in a non-interactive shell.
Ignore; does not affect `java`, `sdk`, or `./gradlew`.

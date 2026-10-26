# Test harness

`PrdfHarness` is a test-only plugin. It builds small test sites in a flat world, then makes a fake
`ServerPlayer` right-click through the real server path (`ServerPlayerGameMode#useItemOn`: interact event,
`ItemStack#useOn` block capture, place event, revert). A listener in the harness plays the protection
plugin: it cancels the fake player's place events, the way a claim plugin treats someone without build
rights. Results are written to `plugins/PrdfHarness/result.txt` and the server stops by itself.

It uses server internals (Mojang-mapped), so it has to be compiled against the server jar it runs on.

```
mvn package                                   # build the plugin first (JDK 25 for the 26.2 API)
test-harness/run.sh /path/to/paper-or-folia-paperclip.jar /path/to/jdk-25/bin/java
```

`run.sh` patches the paperclip jar, compiles the harness, then runs the server twice (without and with
the plugin) and prints one verdict per scenario. Every "without" portal scenario should say DUPED and
every "with" scenario CLEAN / VANILLA / RESTORED.

Never put the harness on a real server: it builds blocks and stops the server when it is done.

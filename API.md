# PAxAPI – API dokumentáció

Megosztott Java library Paper és Velocity Minecraft pluginokhoz. Cél: az adatbázis-kapcsolat, a config-betöltés, a parancs-regisztráció, a hang lejátszás és a szöveg-formázás ismétlődő, hibalehetőségekkel teli boilerplate-jét egyszer megírni, jól tesztelve, és minden plugin ugyanazt a (kikeményített) implementációt használja.

Package: `hu.patriksgit.paxapi`. Öt modulra oszlik:

| Modul | Package | Mire jó |
|---|---|---|
| [Database](#database-modul) | `hu.patriksgit.paxapi.database` | MySQL + HikariCP kapcsolatkezelés, query helperek |
| [Config](#config-modul) | `hu.patriksgit.paxapi.config` | YAML config betöltés, típusos getterek |
| [Command](#command-modul) | `hu.patriksgit.paxapi.command` | Platform-független parancsfa (Paper + Velocity) |
| [Sound](#sound-modul) | `hu.patriksgit.paxapi.sound` | Egysoros hanglejátszás mindkét platformon |
| [Text](#text-modul) | `hu.patriksgit.paxapi.text` | Szín/formázás/placeholder/messages.yml kezelés |

Minden modul **platform-független** ott, ahol lehet (Database, Config, Text alapvetően pure Java/JDBC), és **platform-specifikus adaptereket** ad ott, ahol a Paper/Velocity API-k eltérnek (Command, Sound).

---

## Tartalomjegyzék

1. [Database modul](#database-modul)
2. [Config modul](#config-modul)
3. [Command modul](#command-modul)
4. [Sound modul](#sound-modul)
5. [Text modul](#text-modul)
6. [Összefoglaló: mit NEM tud a PAxAPI](#összefoglaló-mit-nem-tud-a-paxapi)

---

## Database modul

`hu.patriksgit.paxapi.database` — hardened MySQL connection pool (HikariCP alapon) + query helperek. Pure JDBC, platform-független.

### Mit tud
- Kikeményített HikariCP pool (query timeout, socket timeout, TLS módok, truststore, blast-radius flag-ek MySQL Connector/J-hez)
- Szinkron **és** async (`CompletableFuture`) query/update/tranzakció helperek
- Batch insert/update chunkolással
- Health-check (`ping`) és periodikus kapcsolat-figyelő watchdog (`watchConnection`)
- Biztonságos, injection-mentes DDL helperek (`ensureColumn`, `ensureIndex`, stb.)
- PII-biztos hibaüzenetek (`DataAccessException`) — alapból nem loggol bound paraméter *értékeket*, csak típusokat

### Mit NEM tud
- **Csak MySQL** — nincs PostgreSQL, MariaDB, SQLite támogatás (bár a Connector/J-specifikus flagek miatt más MySQL-kompatibilis driverrel sem garantált a működés)
- **A library sosem indít saját szálat** — minden szinkron helper a hívó szálán fut; az async metódusok is csak egy általad megadott `Executor`-t/`ScheduledExecutorService`-t csomagolnak be, nem hoznak létre saját thread poolt
- Nincs write-back / config-migráció a `DatabaseConfig`-hoz — csak beolvasás
- Nincs beépített schema-verzió-követés (csak idempotens `ensureColumn`/`ensureIndex` van, ami minden induláskor lefuttatható)
- `poolSize`-nak nincs felső korlátja, és negatív timeout-értékeket csendben ignorál (nem validálja/warningol)

### `DatabaseConfig` — kapcsolat konfiguráció

Immutable record, `builder()`-rel épül. Kötelező mezők: `host`, `database`, `username`, `password`, `sslMode`.

```java
DatabaseConfig cfg = DatabaseConfig.builder()
    .host("localhost")
    .port(3306)                       // default: 3306
    .database("mineauth")
    .username("root")
    .password("titkos-jelszo")
    .sslMode(DatabaseConfig.SslMode.VERIFY_CA)   // DISABLED / REQUIRED / VERIFY_CA / VERIFY_IDENTITY
    .poolSize(10)                     // default: 10
    .connectionTimeoutMs(30_000)
    .idleTimeoutMs(600_000)
    .maxLifetimeMs(1_800_000)
    .debugParams(false)               // true = bound paraméter ÉRTÉKEK is a hibaüzenetben (csak debugra!)
    .poolName("MineAuth-DB")          // opcionális, JMX-safe pool-név
    .trustStore("file:/etc/ssl/ca.jks", "jelszo", "JKS")  // opcionális, csak file: URL engedett
    .build();
```

`SslMode.parse(String)` — configból beolvasott string → enum, elfogad legacy boolean formát is (`true`/`false`/`yes`/`no`/`on`/`off`), és **fail-closed**: ismeretlen érték kivételt dob, nem csendesen degradál TLS-t.

```java
DatabaseConfig.SslMode mode = DatabaseConfig.SslMode.parse(config.getString("ssl-mode", "VERIFY_CA"));
```

### `Database` — a fő belépési pont

```java
Logger log = LoggerFactory.getLogger("MineAuth");
Database db = new Database(cfg, log);
// ... plugin élete alatt használod ...
db.close();  // onDisable()-ben
```

Van egy harmadik konstruktor is, ha bármi olyan HikariCP-beállítást akarsz, amit a library nem modellez:

```java
Database db = new Database(cfg, log, hikariConfig -> {
    hikariConfig.setRegisterMbeans(true);
});
```
*(A customizer ELŐBB fut, mint a biztonság-kritikus beállítások — azokat, pl. driver class, blast-radius flagek, truststore, nem tudod felülírni vele.)*

#### Szinkron query helperek

```java
// SELECT, minden sor mapped
List<String> names = db.query(
    "SELECT name FROM players WHERE points > ?",
    ps -> ps.setInt(1, 100),
    rs -> rs.getString("name")
);

// SELECT, csak az első sor (Optional)
Optional<Integer> points = db.queryFirst(
    "SELECT points FROM players WHERE uuid = ?",
    ps -> ps.setBytes(1, uuidBytes),
    rs -> rs.getInt("points")
);

// INSERT/UPDATE/DELETE, affected rows
int updated = db.update(
    "UPDATE players SET points = points + ? WHERE uuid = ?",
    ps -> { ps.setInt(1, 10); ps.setBytes(2, uuidBytes); }
);

// Paraméter nélküli overloadok is léteznek:
List<String> all = db.query("SELECT name FROM players", rs -> rs.getString("name"));
int deleted = db.update("DELETE FROM sessions WHERE expired = true");
```

Minden helper automatikusan:
- 30 másodperces **query timeout**-ot állít be (nem fagyhat le örökre a hívó szál, gyakran ez a main thread)
- PII-biztos `DataAccessException`-t dob hiba esetén (lásd lejjebb)
- Ellenőrzi, hogy nem vagy-e éppen egy `tx()`/`batch()` body-ban (lásd ott)

#### Tranzakció (`tx`)

```java
db.tx(c -> {
    // A c Connection-t KELL használnod itt — a db.update(...) stb. NEM csatlakozik ehhez a tranzakcióhoz!
    try (PreparedStatement ps = c.prepareStatement("UPDATE players SET points = points - ? WHERE uuid = ?")) {
        ps.setInt(1, 50); ps.setBytes(2, senderUuid); ps.executeUpdate();
    }
    try (PreparedStatement ps = c.prepareStatement("UPDATE players SET points = points + ? WHERE uuid = ?")) {
        ps.setInt(1, 50); ps.setBytes(2, receiverUuid); ps.executeUpdate();
    }
    return null;  // vagy egy tetszőleges visszatérési érték
});
```
Commit sikeren, rollback + rethrow bármilyen hibán. **Fontos**: a `tx()` body-n belül SOHA ne hívd a `db.query`/`db.update`/stb.-t (vagy `db.tx`/`db.batch`-et sem, még async formában sem) — azok egy MÁSIK, saját pool-connectiont nyitnának, ami nem csatlakozik ehhez a tranzakcióhoz, és kis pool-méret esetén akár deadlockolhat is. Ezt a library **kikényszeríti**: ha megpróbálod, `DataAccessException`-t dob.

#### Batch (`batch`)

```java
int[] affected = db.batch(
    "INSERT INTO log (player, action) VALUES (?, ?)",
    logEntries,                                   // Iterable<LogEntry>
    (ps, entry) -> {
        ps.setString(1, entry.player());
        ps.setString(2, entry.action());
    }
);
```
100-as chunkokban futtatja (`BATCH_CHUNK`), EGY tranzakcióban — ha egy chunk elhasal, semelyik korábbi chunk sem marad commitolva (all-or-nothing).

#### Async helperek

Minden szinkron metódusnak van async párja, amihez **neked kell megadnod egy `Executor`-t** (pl. a saját Bukkit/Velocity async scheduleredet becsomagolva, vagy egy saját `ExecutorService`-t) — a library maga sosem indít szálat.

```java
Executor async = Executors.newVirtualThreadPerTaskExecutor(); // vagy a saját scheduler-wrappered

db.queryAsync("SELECT name FROM players", rs -> rs.getString("name"), async)
    .thenAccept(names -> { /* eredmény feldolgozása */ });

db.queryFirstAsync("SELECT points FROM players WHERE uuid=?", ps -> ps.setBytes(1, uuid),
        rs -> rs.getInt("points"), async)
    .thenAccept(opt -> opt.ifPresent(p -> System.out.println(p)));

db.updateAsync("UPDATE players SET points=points+1 WHERE uuid=?", ps -> ps.setBytes(1, uuid), async);

db.txAsync(c -> { /* ... */ return null; }, async);

db.batchAsync("INSERT INTO log VALUES (?)", entries, (ps, e) -> ps.setString(1, e), async);
```

Hiba esetén a hiba a visszakapott `CompletableFuture`-ön keresztül jön (a szokásos módon `ExecutionException`/`CompletionException`-be csomagolva, a cause a `DataAccessException`), **nem** azonnali `throw`-ként.

**Fontos**: ha egy async metódust `tx()`/`batch()` body-ból hívsz meg, az is `DataAccessException`-t dob — ugyanaz a védelem vonatkozik rájuk, mint a szinkron helperekre.

#### Health-check (`ping`) és kapcsolat-figyelő (`watchConnection`)

```java
if (db.ping()) {                       // 5 másodperces timeout-tal, sosem dob
    // DB él
}
boolean alive = db.ping(10);           // saját timeout másodpercben

CompletableFuture<Boolean> future = db.pingAsync(async);
```

⚠️ `ping()`/`pingAsync()` is saját pool-connectiont nyit, ezért — ugyanúgy, mint a query/update/tx/batch helperek — ha `tx()`/`batch()` body-ból hívod, ez is `DataAccessException`-t dob.

A HikariCP magától újra próbál kapcsolatot nyitni, ha kérsz egy connectiont — de nincs proaktív jelzés, ha a DB **futás közben** hal meg és jön vissza. Erre van a `watchConnection`:

```java
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

ScheduledFuture<?> handle = db.watchConnection(scheduler, 30, TimeUnit.SECONDS, up -> {
    if (up) log.info("Adatbázis kapcsolat helyreállt!");
    else    log.warn("Adatbázis kapcsolat megszakadt!");
});

// vagy az alapértelmezett 30s intervallummal:
db.watchConnection(scheduler, up -> { /* ... */ });

// leállítás (pl. onDisable()):
handle.cancel(false);
```
Csak **állapotváltozáskor** hívja a callbacket (fent→lent vagy lent→fent), nem minden ticken. A callbacket bevédi — ha az általad adott callback dob egy kivételt, azt elnyeli, mert egy `ScheduledExecutorService` **csendben leállítaná örökre** a periodikus taskot, ha egyszer uncaught exception-t kapna.

#### DDL helperek (schema migráció)

```java
try (Connection c = db.getConnection()) {
    db.ensureColumn(c, "players", "coins", "INT DEFAULT 0");         // ADD COLUMN, idempotens
    db.ensureColumnType(c, "players", "coins", "BIGINT");            // MODIFY COLUMN, idempotens
    db.ensureIndex(c, "players", "idx_coins", "(coins)");            // CREATE INDEX, idempotens
}
```
⚠️ **Trust-kontrakt**: a `table`/`column`/`definition`/`columns` argumentumok **fejlesztő-kontrollált literálok** kell legyenek, SOHA config- vagy felhasználói input — DDL-be vannak fűzve (identifier nem bindelhető JDBC paraméterként). Egy whitelist regex védi az injectiont, de ettől függetlenül kódnak kezeld, nem adatnak.

#### `DataAccessException` — a hibatípus, amit el fogsz kapni

`RuntimeException`, minden query/update/tx/batch hiba esetén ezt dobja. A `getMessage()` tartalmazza az SQL-t + a paraméterek **típusát** (nem az értékét, hacsak `debugParams=true` nincs beállítva a configban) + a driver hibakódját/SQLState-jét.

```java
try {
    db.update("UPDATE players SET points=? WHERE uuid=?", ps -> { /* ... */ });
} catch (DataAccessException e) {
    log.error("DB hiba: {}", e.getMessage());   // PII-safe log sor
    // e.getCause() -> az eredeti SQLException, DE az NEM sanitizált (ne logold közvetlenül!)
}
```

#### `Sql` — funkcionális interfészek a helperekhez

```java
Sql.Binder.NONE                                    // paraméter nélküli binder
Sql.Binder binder = ps -> ps.setString(1, "x");
Sql.RowMapper<String> mapper = rs -> rs.getString(1);
Sql.BiBinder<MyType> biBinder = (ps, item) -> ps.setString(1, item.name());
Sql.TxBody<Void> body = c -> { /* ... */ return null; };
```

---

## Config modul

`hu.patriksgit.paxapi.config` — immutable YAML konfiguráció-snapshot típusos getterekkel és dot-notation kulcs-eléréssel. SnakeYAML `SafeConstructor` alapon (nincs YAML-alapú kód-végrehajtási kockázat).

### Mit tud
- Automatikus fájl-extraction, ha a config még nem létezik (`getResource(...)` → disk)
- Dot-notation kulcs elérés (`"database.host"`)
- Típusos getterek `String`/`int`/`long`/`double`/`float`/`boolean`/lista/enum-hoz, mindegyik null-safe default-tal + WARN logolással hibás típus esetén
- `require(key)` kötelező kulcsokhoz, ami `ConfigException`-t dob, ha hiányzik
- `section(path)` — al-config nézet, relatív kulcs-eléréssel
- Thread-safe hot-reload minta (volatile mező + atomikus cserés `load()`)
- Ciklus-detektálás önhivatkozó YAML anchor/alias ellen (nem omlik össze egy `a: &x\n loop: *x` mintájú configon)

### Mit NEM tud
- **Nincs write-back** — csak olvas, nem tud visszaírni a fájlba
- **Nincs config-migráció** — ha egy plugin frissítéskor egy új kötelező kulcsot vár, egy régi, hiányos configon a `require(...)` hívás elhal induláskor, amíg valaki manuálisan nem frissíti a fájlt
- Nincs `Set`/`byte[]`/`Date` (YAML `!!set`/`!!binary`/`!!timestamp`) natívan visszaadó getter — ezek elméletileg átjutnának a mély-immutabilizáción felöltetlenül, de jelenleg nincs public getter, ami ezeket kiadná

### Betöltés

```java
private volatile ConfigFile config;

void onEnable() throws IOException {
    config = ConfigFile.load(dataDir.resolve("config.yml"), getResource("config.yml"), logger);
}

void reload() throws IOException {
    config = ConfigFile.load(dataDir.resolve("config.yml"), getResource("config.yml"), logger);
    // atomikus csere — a régi olvasók a régi (konzisztens) snapshotot látják, amíg be nem fejeződnek
}

// Ha nem kell auto-extract (a fájl garantáltan létezik):
ConfigFile cfg = ConfigFile.load(path, logger);
```

### Típusos getterek

```java
String host   = config.getString("database.host", "localhost");
int    port   = config.getInt("database.port", 3306);
int    ranged = config.getInt("pool-size", 10, 1, 100);         // [min,max] range-check, kilóg -> def
long   big    = config.getLong("max-bytes", 1_000_000L);
double ratio  = config.getDouble("multiplier", 1.0);
float  volume = config.getFloat("sound.volume", 1.0f);          // Minecraft float-konvenciókhoz (volume/pitch)
boolean on    = config.getBoolean("features.enabled", true);

List<String> aliases = config.getStringList("commands.help.aliases", List.of());
List<String> aliases2 = config.getStringList("commands.help.aliases");  // üres lista default (nincs kell 2. arg)

MyEnum mode = config.getEnum(MyEnum.class, "mode", MyEnum.DEFAULT);     // case-insensitive
```

Minden numerikus getter:
- Elfogad String-ből parseolt értéket is (`"42"` → `42`)
- NaN/Infinity-t elutasítja (`getDouble`/`getFloat`)
- Túlcsordulást (int/long/float range-en kívül) elutasítja és a default-ot adja — **nem** csendben levágja

### Kötelező kulcsok

```java
String password;
try {
    password = config.require("database.password");
} catch (ConfigException e) {
    logger.error("Hiányzó kötelező config kulcs: {}", e.getMessage());
    // startup megszakítása
    throw e;
}
```

### Section-nézet, kulcsok listázása

```java
ConfigFile db = config.section("database");
String host = db.getString("host", "localhost");   // ténylegesen "database.host"-ot olvas

Set<String> rewardLevels = config.section("rewards").keys();   // dinamikus, felhasználó-definiált kulcsok
for (String level : rewardLevels) {
    int amount = config.getInt("rewards." + level + ".amount", 0);
}
```

```java
boolean has = config.contains("optional-feature.enabled");  // true, ha a kulcs létezik és nem null
```

### `ConfigException`

`IOException` leszármazott — kötelező kulcs hiánya vagy hibás YAML esetén dobódik (`require()`, vagy magából a `load()`-ból malformed YAML esetén).

---

## Command modul

`hu.patriksgit.paxapi.command` — platform-független, immutable parancsfa (`CommandSpec`) + dispatcher (`CommandDispatcher`), plusz Paper/Velocity-specifikus regisztráló adapterek.

### Mit tud
- Egy `CommandSpec<S>` fát írsz meg egyszer, generikusan a sender-típusra (`S`) — ugyanaz a leírás Paper-en (`CommandSender`) és Velocity-n (`CommandSource`) is működik a megfelelő adapterrel
- Beépített: permission-check, requirement-predicate, player-only gate, per-key cooldown gate (`CooldownTracker`), alias-kezelés, tab-completion, hiba-routing (`onError`/`onDenied`/`onUnknown`)
- Minden infrastruktúra-hívás (permission/requirement/playerOnly check, és minden callback) védett — egy dobó `SenderAdapter` implementáció vagy egy dobó callback sem tudja lefagyasztani/kicsapni a dispatchert
- **Szándékos kivétel**: ha a HANDLERED dob egy kivételt és nincs `onError` beállítva, az **propagál** (nem nyeli el csendben) — hogy a handler-bugok látszódjanak a szerver logban, ne tűnjenek el nyomtalanul

### Mit NEM tud
- Nincs beépített async execution support (a handler szinkron fut a hívó szálon — Velocity-n ez lehet a network thread, Paper-en a main thread)
- Nincs strukturált argumentum-parsing helper (csak `String[] args`-t kapsz, saját magadnak kell parse-olnod)
- `CommandSpec.Builder.group()` nem detektál minden ütközést: ha egy új alias megegyezik egy MEGLÉVŐ testvér KANONIKUS nevével, azt **szándékosan** nem dobja hibaként — a kanonikus név mindig nyer, a regisztrálás sorrendjétől függetlenül (ez tesztelt, dokumentált viselkedés, nem bug)

### `CommandSpec` — a parancsfa

```java
CommandSpec<CommandSender> spec = CommandSpec.<CommandSender>root("mineauth")
    .permission("mineauth.admin")
    .onDenied(sender -> sender.sendMessage("Nincs jogosultságod!"))
    .onUnknown((sender, sub) -> sender.sendMessage("Ismeretlen alparancs: " + sub))
    .onError((sender, t) -> logger.warn("Parancs hiba", t))
    .handler(ctx -> ctx.sender().sendMessage("MineAuth admin panel"))   // root handler (üres arg esetén)

    .sub("reload", ctx -> {
        plugin.reloadConfig();
        ctx.sender().sendMessage("Config újratöltve.");
    })

    .group("maintenance", m -> m
        .aliases("maint")                       // vagy: .aliasesFrom(configListedAliases)
        .requires(s -> s.hasPermission("mineauth.maintenance"), s -> s.sendMessage("Nincs jog!"))
        .sub("on",  ctx -> { /* ... */ })
        .sub("off", ctx -> { /* ... */ })
        .sub("status", ctx -> { /* ... */ }))

    .group("kick", k -> k
        .playerOnly(s -> s.sendMessage("Csak játékos futtathatja!"))
        .arg(0, PaperCompleters.onlinePlayers())          // tab-completion az 1. argumentumra
        .handler(ctx -> {
            String target = ctx.arg(0);
            String reason = ctx.argCount() > 1 ? String.join(" ", Arrays.asList(ctx.args()).subList(1, ctx.argCount())) : "N/A";
            // ...
        }))

    .build();
```

Builder-metódusok:
- `.permission(String)` — null = nincs permission-check
- `.aliases(String... )` / `.aliasesFrom(List<String>)` — utóbbi kifejezetten configból jövő alias-listákhoz (**nem** overload, külön névvel, mert egy `aliases(List<String>)` overload minden meglévő `.aliases(null)` hívást ambiguous compile error-rá tenne)
- `.requires(Predicate<S> pred, Consumer<S> onFail)` — custom gate; ⚠️ Velocity-n a predicate **tab-completion alatt async szálon is fut**, legyen thread-safe
- `.playerOnly(Consumer<S> onFail)`
- `.cooldown(CooldownTracker tracker, Supplier<Duration> duration, BiConsumer<S,Duration> onCooldown)` — per-key (sender-identitás alapú) cooldown gate; csak `execute()`-ban fogyasztódik, `complete()`/tab-completion sosem consumálja; ld. lentebb
- `.handler(CommandHandler<S>)` — mit csináljon a végén
- `.sub(String name, CommandHandler<S>)` — rövidítés `.group(name, b -> b.handler(h))`-ra
- `.group(String name, Consumer<Builder<S>> child)` — egymásba ágyazható alparancsok
- `.arg(int index, ArgumentCompleter<S>)` — tab-completion egy adott pozícióra
- `.onDenied(Consumer<S>)` / `.onUnknown(BiConsumer<S,String>)` / `.onError(BiConsumer<S,Throwable>)` — a fába lefelé "öröklődnek" (a legutolsó nem-null beállítás nyer az adott ágon)

### Cooldown gate (`.cooldown()` + `CooldownTracker`)

A `CooldownTracker` egy önálló, sender-identitás alapú, per-kulcs cooldown-nyilvántartó. Egy tracker egy parancshoz tartozik — te hozod létre és tartod életben (pl. egy plugin-mezőben), és te ütemezed rá az `evictOlderThan(...)`-t, mert a lib sosem indít saját szálat.

```java
CooldownTracker reloadCooldown = new CooldownTracker();

CommandSpec<CommandSender> spec = CommandSpec.<CommandSender>root("mineauth")
    .sub("reload", ctx -> { /* ... */ })
    .group("setspawn", g -> g
        .cooldown(reloadCooldown, () -> Duration.ofSeconds(config.getInt("setspawn-cooldown-seconds", 5)),
            (sender, remaining) -> sender.sendMessage("Várj még " + remaining.toSeconds() + " másodpercet!"))
        .handler(ctx -> { /* ... */ }))
    .build();

// máshol, periodikusan (pl. a saját scheduleredből):
reloadCooldown.evictOlderThan(Duration.ofHours(1));
```

- A kulcs a `SenderAdapter.identity(S)` — `PaperSenderAdapter`/`VelocitySenderAdapter` játékosnál a `UUID.toString()`-ot adja, egyébként (konzol, command block, RCON) `null`-t. **Null identitás = mentesülés a cooldown alól**, nem közös vödör.
- A cooldown **csak `CommandDispatcher.execute()`-ben fogyasztódik** — `complete()` (tab-completion) sosem érinti, még akkor sem, ha Velocity-n a `requires()`-hez hasonlóan off-thread fut.
- A gate-sorrend utolsó tagja: `permission` → `requirement` → `playerOnly` → `cooldown`. Egy megtagadott próbálkozás (bármelyik korábbi gate-en) sosem fogyasztja el a cooldownt.
- Egy blokkolt próbálkozás **nem** tolja el az ablakot — a cooldown mindig az utolsó ENGEDÉLYEZETT használattól számít.
- `duration` egy `Supplier<Duration>`, minden ellenőrzésnél frissen kiértékelve — configból élőben változtatható a hossz, a `CommandSpec`-fa újraépítése nélkül.
- `onCooldown` kötelező (`Objects.requireNonNull`), ugyanúgy mint a `requires()`/`playerOnly()` `onFail`-je.
- Ha a handler kivételt dob, a cooldown akkor is elhasználódik (gate-átengedéskor rögzít, a handler előtt).
- ⚠️ Ha saját (nem Paper/Velocity) `SenderAdapter`-t írsz és `.cooldown()`-t is használsz, kötelező felülírni az `identity()`-t — a default implementáció mindenkinek `null`-t ad vissza, ami **csendben, hibaüzenet nélkül** kikapcsolja a cooldownt mindenkire nézve.
- ⚠️ A `duration` Supplier soha ne adjon vissza `null`-t — a `tryAcquire` ezt `NullPointerException`-nel jelzi, amit a dispatcher elkap és — ha nincs `onError` beállítva — csendben elnyel, így a parancs látszólag "beragad" válasz nélkül.

### `CommandContext`

A handler ezt kapja:

```java
.handler(ctx -> {
    S sender = ctx.sender();
    String label = ctx.label();               // amivel a felhasználó hívta (alias vagy kanonikus név)
    List<String> path = ctx.path();            // a matched alparancs-útvonal, pl. ["maintenance", "on"]
    String[] args = ctx.args();                 // a maradék argumentumok (defenzív kópia minden hívásnál)
    String first = ctx.arg(0);                   // null, ha nincs annyi argumentum
    int count = ctx.argCount();
})
```

### `CommandDispatcher` — direkt használat (ha nem platform-adapteren keresztül regisztrálsz)

```java
CommandDispatcher<CommandSender> dispatcher = new CommandDispatcher<>(spec, PaperSenderAdapter.INSTANCE, logger);
dispatcher.execute(sender, label, args);
List<String> suggestions = dispatcher.complete(sender, args);
```

### Platform-adapterek

**Paper:**
```java
// plugin.yml-ben deklarálva kell legyen a "mineauth" parancs a commands: alatt!
PaperCommands.register(this, spec);                 // vagy logger-rel: register(this, spec, logger)

// Élő alias-csere (pl. config-reload után):
PaperCommands.applyAliases(this, "mineauth", List.of("ma", "mauth"));
```

**Velocity:**
```java
VelocityCommands.register(proxyServer, this, spec);              // vagy logger-rel is
VelocityCommands.unregister(proxyServer, spec);                  // pl. onDisable / reload előtt
```

**Tab-completion helperek:**
```java
PaperCompleters.onlinePlayers()                       // csak main threadről hívható (Bukkit player state)
VelocityCompleters.onlinePlayers(proxyServer)         // Velocity-n nincs vanish-API, mindenki látszik
Completers.of("yes", "no")                            // statikus lista
Completers.list(() -> myDynamicCollection)             // dinamikus lista (Supplier)
Completers.enumValues(MyEnum.class)                    // enum nevek kisbetűsen
```

**`SenderAdapter<S>`** — ha saját platformra (vagy teszthez) írnál adaptert:
```java
public interface SenderAdapter<S> {
    boolean hasPermission(S sender, String permission);
    boolean isPlayer(S sender);
}
```
`PaperSenderAdapter.INSTANCE` és `VelocitySenderAdapter.INSTANCE` a kész implementációk.

### `MessageHooks` — `onDenied`/`onUnknown`/`playerOnly` a `MessagesFile`-ból

Ha üzeneteidet egy `MessagesFile`-ból akarod táplálni a hibaüzenetekhez, nem kell kézzel megírni a callbackeket:

```java
MessageHooks<CommandSender> hooks = MessageHooks.of(messagesFile)
    .noPermissionKey("general.no-permission")   // default
    .unknownKey("admin.unknown-subcommand")     // default
    .playerOnlyKey("admin.player-only");        // default

CommandSpec.<CommandSender>root("mineauth")
    .onDenied(hooks.denied())
    .onUnknown(hooks.unknown())
    // egy adott .playerOnly(...) node-nál:
    .group("kick", k -> k.playerOnly(hooks.playerOnly()).handler(...))
    .build();
```

---

## Sound modul

`hu.patriksgit.paxapi.sound` — egysoros hang-lejátszás. `PaperSounds` Bukkit `Player`-re, `VelocitySounds` Velocity `Player`-re (Adventure `Sound`-on keresztül).

### Mit tud
- Kulcs-validáció lejátszás előtt — rossz sound key esetén **csendben nem csinál semmit**, nem dob kivételt, mindkét platformon egyformán
- `playAll` egyszer validál a listán való végigmenetel ELŐTT — egy rossz kulcs nem szakítja félbe a batch-et, mindenkit kihagy egységesen (nem "az első néhány játékos kap hangot, a többi nem")
- `null` játékosokat a listában automatikusan kihagyja

### Mit NEM tud
- **Nincs volume/pitch clamp** — extrém (negatív, NaN, Infinity) értékeket átenged a platform API-nak, ott a viselkedés nem garantált
- **Nincs mute/toggle hook** — ha per-player elnémítást akarsz, azt neked kell megírnod, és a hívás elé feltenned a feltételt
- **Nincs fallback/default sound key** — rossz kulcs = néma, nincs "próbáld meg egy másik kulccsal" logika
- Az aláhúzásos kulcsformátum (`ENTITY_PLAYER_LEVELUP`) **átmegy** a validáláson (mert szintaktikailag legális), de **más** (jellemzően nem létező) resource path-ra mutat, mint a pontos forma — mindig pontos formát (`entity.player.levelup`) használj
- `PaperSounds`/`VelocitySounds` metódusait csak a megfelelő szálon hívd (Paper: main thread — a `Player.getLocation()` main-thread-only API)

### Paper

```java
PaperSounds.play(player, "entity.player.levelup");                                    // default: MASTER, vol=1.0, pitch=1.0
PaperSounds.play(player, "entity.player.levelup", 0.5f, 1.2f);                          // custom volume/pitch
PaperSounds.play(player, "entity.player.levelup", SoundCategory.MASTER, 0.5f, 1.2f);    // + custom category

PaperSounds.playAll(onlinePlayers, "entity.experience_orb.pickup");                     // Iterable<Player>-nek
PaperSounds.playAll(onlinePlayers, "entity.experience_orb.pickup", 1.0f, 1.0f);
PaperSounds.playAll(onlinePlayers, "entity.experience_orb.pickup", SoundCategory.PLAYERS, 1.0f, 1.0f);
```

### Velocity

```java
VelocitySounds.play(player, "entity.player.levelup");                                       // default: MASTER
VelocitySounds.play(player, "entity.player.levelup", 0.5f, 1.2f);
VelocitySounds.play(player, "entity.player.levelup", Sound.Source.MASTER, 0.5f, 1.2f);

VelocitySounds.playAll(allPlayers, "entity.experience_orb.pickup");
VelocitySounds.playAll(allPlayers, "entity.experience_orb.pickup", 1.0f, 1.0f);
VelocitySounds.playAll(allPlayers, "entity.experience_orb.pickup", Sound.Source.PLAYERS, 1.0f, 1.0f);
```

---

## Text modul

`hu.patriksgit.paxapi.text` — szín/formázás/placeholder-kezelés egy belépési ponton (`TextUtil`), egy YAML-alapú üzenetkezelő (`MessagesFile`), és egy fluent builder interaktív Componentekhez (`ComponentBuilder`).

### Mit tud
- Legacy `&`/`§` kódok, hex színek (`&#RRGGBB` és `<#RRGGBB>`), teljes MiniMessage szintaxis egyben
- Injection-safe `%key%` placeholder-helyettesítés (String **és** előre elkészített Component értékekkel is)
- Külső placeholder-rendszer (PlaceholderAPI/MiniPlaceholders) becsatolása egy `PlaceholderExpander`-en keresztül, alapból escape-elt kimenettel (nem tud interaktív taget becsempészni)
- Rosszul formázott MiniMessage bemenet nem dob kivételt — plain-text fallback
- `MessagesFile`: `messages.yml` betöltés, `%prefix%` + **többszörös, elnevezett prefixek** (`prefix:` mint map), YAML lista vagy sima string kulcsok egységesen, thread-safe hot-reload

### Mit NEM tud
- Nincs `TextUtil.broadcast(Iterable<Audience>, Component)` ad-hoc helper — csak a `MessagesFile.broadcast(...)` létezik (kulcs-alapú); egy ad-hoc Component szétküldéséhez saját for-ciklus kell
- A `get()`/`getList()`/`formatChat()` metódusok a `MessagesFile`-on **nem** futtatják a `PlaceholderExpander`-t (PAPI/MiniPlaceholders) — csak a `send*`/`sendChat` metódusok, amik egy `Audience`-t kapnak
- A process-global `expander`/`debugLogger` beállítás **last-writer-wins**, megosztott minden pluginon, ami ezt a library-instance-t használja — `TextUtil.reset()`-et hívj `onDisable()`-ben, ha ez shaded/provided library nálad

### `TextUtil` — a fő belépési pont

```java
// Egyszerű parse, placeholder nélkül
Component c = TextUtil.parse("&aÜdvözlünk, &b%name%&a!");   // %name% itt még nem lesz kicserélve

// Placeholderrel (String érték — injection-safe, a value-ban lévő MiniMessage tag literális marad)
Component c2 = TextUtil.parse("&aÜdvözlünk, &b%name%&a!", Map.of("name", playerName));

// Két placeholder-forrás egyszerre (String + előre elkészített Component)
Component c3 = TextUtil.parse("%prefix% %name%",
    Map.of("name", playerName),                      // injection-safe string
    Map.of("prefix", TextUtil.parse("&8[Staff]")));    // megbízható, előre-parseolt Component

// Player input formázása — csak szín/formázókód engedett, minden MiniMessage tag literális marad
Component safe = TextUtil.parseLegacyOnly(chatMessage);

// Formázás-mentesítés (plain text)
String plain = TextUtil.strip("&aSzia &b%name%!");     // "Szia %name%!" — a %name% MARAD, ha nincs feloldva

// Component-értékű placeholderek (pl. AsyncChatEvent Component-je)
Component line = TextUtil.formatChat(
    "%prefix% %name% &8» &f%message%",
    Map.of("prefix", TextUtil.parse(prefix), "name", Component.text(player.getName()), "message", event.message())
);
```

**Küldés `Audience`-nek:**
```java
TextUtil.sendChat(player, "&aÜdv!");                                    // egy sor
TextUtil.sendChat(player, "&aÜdv, %name%!", Map.of("name", name));      // placeholderrel
TextUtil.sendChat(player, List.of("&aElső sor", "&bMásodik sor"));      // több sor, mindegyik külön chat-üzenet
TextUtil.send(player, "&aÜdv!");                                        // = sendChat, visszafelé-kompatibilis alias

TextUtil.sendActionBar(player, List.of("&e%points% pont"), Map.of("points", "42"));

TextUtil.sendTitle(player, List.of("&6Szint fel!", "&7Grat!", "10,60,10"));  // title, subtitle, timing (tick-ekben)

TextUtil.sendTablist(player, headerLines, footerLines);

// Kick/disconnect Component (több sor eggyé fűzve \n-nel):
player.kick(TextUtil.joinLines(List.of("&cKirúgva!", "&7Indoklás: AFK")));
```

**Interaktív Component builder:**
```java
Component msg = TextUtil.builder("&7%sender% &8→ &7%receiver%")
    .placeholder("sender", senderName)
    .placeholder("receiver", targetName)
    .hover("&7Kattints a válaszhoz!")
    .suggest("/msg " + senderName + " ")
    .build();
```
`ComponentBuilder` metódusai: `.placeholder(k,v)`, `.placeholders(map)`, `.hover(String|Component)`, `.suggest(cmd)`, `.run(cmd)`, `.url(url)`, `.copy(text)`, `.build()`. A click-metódusok (`suggest`/`run`/`url`/`copy`) közül az utolsó hívott nyer. ⚠️ `suggest`/`run` parancs-stringje és `url`/`copy` argumentuma **fejlesztői input** legyen — SOHA ne építsd player-input-ból (parancs-injection kockázat); `hover` esetén ez "csak" vizuális spoofing-kockázat (a tooltip nem kattintható).

**Külső placeholder-rendszer (PAPI/MiniPlaceholders):**
```java
// onEnable()-ben, egyszer:
TextUtil.setExpander((text, playerObj) -> {
    Player p = (Player) playerObj;
    return PlaceholderAPI.setPlaceholders(p, text);
});
// Alapból az expander kimenete escape-elve van (nem tud <hover>/<click>-et becsempészni).
// Ha 100%-ig megbízol az expanderben és MiniMessage-ként akarod parse-oltatni a kimenetét:
TextUtil.setExpander(myExpander, true);

TextUtil.setDebugLogger(logger);   // opcionális: MiniMessage parse-hiba és expander-hiba WARN-loggolása

// onDisable()-ben, HA a library provided/shaded és több plugin osztja:
TextUtil.reset();
```

### `MessagesFile` — `messages.yml` kezelés

```java
private volatile MessagesFile messages;

void onEnable() throws IOException {
    messages = MessagesFile.load(dataDir.resolve("messages.yml"), getResource("messages.yml"), logger);
}
void reload() throws IOException {
    messages.reload();   // atomikus snapshot-csere, thread-safe
}
```

`messages.yml`:
```yaml
prefix: "&8[MineAuth]&r "     # sima string prefix -> %prefix%
# VAGY:
prefix:                        # map = elnevezett prefixek, automatikusan behelyettesítve %name-prefix%-ként
  staffchat: "&8[SC]&r "
  helpop: "&8[HelpOp]&r "

general:
  no-permission: "%prefix% &cNincs jogosultságod!"
admin:
  help:
    - "%prefix% &e/reload &7- config újratöltés"
    - "%prefix% &e/kick &7- kirúgás"
staffchat:
  message: "%staffchat-prefix%&f%sender%: %message%"    # elnevezett prefix auto-feloldva
```

```java
Component msg = messages.get("general.no-permission");
Component msg2 = messages.get("staffchat.message", Map.of("sender", name, "message", text));

List<Component> help = messages.getList("admin.help");    // YAML lista, soronként Component

messages.send(player, "general.no-permission");                        // string ÉS lista kulcsot is kezel
messages.send(player, "staffchat.message", Map.of("sender", name, "message", text));

messages.broadcast(onlinePlayers, "general.server-restart");           // minden Audience-nek elküldi

Component line = messages.formatChat("staffchat.message",
    Map.of("sender", Component.text(name), "message", event.message()));   // Component-értékű placeholderekkel

messages.sendTitle(player, "welcome.title");        // lines[0]=title, [1]=subtitle, [2]=timing a YAML listából
messages.sendActionBar(player, "combat.low-health");

String raw = messages.rawPrefix();                  // a %prefix% nyers string értéke (parse előtt)
String named = messages.prefix("staffchat");        // egy elnevezett prefix nyers string értéke
```

⚠️ A `get`/`getList`/`send`/`broadcast`/`formatChat`/`sendTitle`/`sendActionBar` közül csak a `send`/`broadcast`/`sendTitle`/`sendActionBar` futtatja a `TextUtil` `PlaceholderExpander`-t (mert azok `Audience`-t kapnak) — a `get`/`getList`/`formatChat` nem.

### `PlaceholderExpander` — a bridge interfész

```java
@FunctionalInterface
public interface PlaceholderExpander {
    String expand(String text, Object player);
}
```
Lásd fent, `TextUtil.setExpander(...)`-nél.

---

## Összefoglaló: mit NEM tud a PAxAPI

Gyors áttekintés azoknak a limitációknak, amikre a fejlesztés során bukkantunk / amiket tudatosan nem oldottunk meg (mert szűk használati esetre lett volna, vagy mert a jelenlegi kód biztonságosabb nélküle):

- **Database**: csak MySQL; nincs config-migráció a `DatabaseConfig`-hoz; nincs beépített schema-verzió-követés; a library sosem indít saját szálat (minden async metódus a TE `Executor`-odat várja)
- **Config**: nincs write-back (csak olvasás); nincs migráció régi configokhoz (egy új `require()`-elt kulcs egy régi configon induláskor elhal, amíg valaki manuálisan nem frissíti)
- **Command**: nincs cooldown/rate-limit hook; nincs async handler-execution támogatás; nincs strukturált argumentum-parser (csak `String[]`)
- **Sound**: nincs volume/pitch clamp; nincs per-player mute/toggle hook; nincs fallback sound key
- **Text**: nincs `TextUtil.broadcast()` ad-hoc helperre (csak `MessagesFile.broadcast()` kulcs-alapon); a `get`/`getList`/`formatChat` nem futtatja a külső placeholder-expandert

Ezek mind **tudatos** döntések vagy egyszerűen "még nem lett rá igény" — ha bármelyik hiányzik neked, jelezd, és a library-ba felvehetjük.

# Member C - Client

`FlightClient.java` implements the console user interface for all six operations.
It reuses the shared classes in `src/D`, especially `ReliableUdpClient`, so one UDP
socket handles requests, replies, retries, simulated request loss, and monitoring
callbacks.

The class intentionally has no `package` declaration because the existing shared
and server classes use the default package.

## Compile

Run from the project root with Java 17:

```powershell
New-Item -ItemType Directory -Force out | Out-Null
$sources = Get-ChildItem src -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
javac -d out $sources
```

## Run

Start the final integrated server in one terminal:

```powershell
java -cp out FlightServer 6789 at-most-once
```

Start the client in another terminal or on another computer:

```powershell
java -cp out FlightClient 127.0.0.1 6789 at-most-once
```

To simulate losing the first outgoing request for operation 6:

```powershell
java -cp out FlightClient 127.0.0.1 6789 at-least-once --drop-first-request-op=6
```

For the callback demonstration, run one client and choose option 4. While that
client is waiting, use another client to choose option 3 and reserve seats on the
same flight.

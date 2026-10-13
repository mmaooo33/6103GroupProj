# SC6103 Distributed Flight Information System

Java 17 implementation of the SC6103 course project. The system uses UDP,
manual byte-array marshalling, callback-based seat monitoring, and selectable
at-least-once or at-most-once invocation semantics.

## Source layout

- `src/B/FlightServer.java`: final integrated server and callback sender.
- `src/B/FlightService.java`, `Flight.java`, `MonitorRegistry.java`: server data
  and business logic.
- `src/C/FlightClient.java`: interactive console client.
- `src/D/`: shared protocol, codec, reliable UDP transport, history and fault
  injection components.
- `src/ProjectSelfTest.java`: shared, framework-free local checks.

All Java classes currently use the default package. Compile the complete source
tree together; do not compile only one subdirectory.

## Compile with Java 17

From the project root in PowerShell, the simplest option is:

```powershell
.\build.ps1 -RunSelfTest
```

The equivalent manual commands are:

```powershell
New-Item -ItemType Directory -Force out | Out-Null
$sources = Get-ChildItem src -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
javac -d out $sources
```

Run the local self-test:

```powershell
java -ea -cp out ProjectSelfTest
```

## Normal run

Server:

```powershell
java -cp out FlightServer 6789 at-most-once
```

Client:

```powershell
java -cp out FlightClient 127.0.0.1 6789 at-most-once
```

Replace `127.0.0.1` with the server computer's LAN address when demonstrating
the system on different computers. Allow inbound UDP traffic to the chosen
server port in the operating-system firewall.

## Monitoring callback demonstration

1. Start the server.
2. Start Client A and choose operation 4 for flight 101.
3. While Client A waits, start Client B and reserve seats on flight 101.
4. Client A should print a callback containing the updated availability.
5. Repeat with two monitoring clients to verify concurrent registrations.

## Fault experiments

Restart the server before each experiment so flight 101 begins with 20 seats.

No loss:

```powershell
java -cp out FlightServer 6789 at-most-once
java -cp out FaultExperimentClient 127.0.0.1 6789 at-most-once --operation=6
```

Simulated request loss:

```powershell
java -cp out FlightServer 6789 at-most-once
java -cp out FaultExperimentClient 127.0.0.1 6789 at-most-once --operation=6 --drop-first-request-op=6
```

At-least-once with the first reply dropped after execution should add seats
twice, producing 40 seats:

```powershell
java -cp out FlightServer 6789 at-least-once --drop-first-reply-op=6
java -cp out FaultExperimentClient 127.0.0.1 6789 at-least-once --operation=6
```

At-most-once with the same reply loss should replay the cached reply without a
second mutation, producing 30 seats:

```powershell
java -cp out FlightServer 6789 at-most-once --drop-first-reply-op=6
java -cp out FaultExperimentClient 127.0.0.1 6789 at-most-once --operation=6
```

Capture the client and server logs from these runs for the lab report. The
backward-compatible `DFlightServer` launcher delegates to `FlightServer`, but
new commands should use `FlightServer` directly.

# Setup and collaboration
## Get started
Install Git, JDK 17, Maven, and an IDE (IntelliJ IDEA, Eclipse, or VS Code with Java support).
Clone the repository and open the root pom.xml as a Maven project:

```sh
git clone https://github.com/mohamadazeem/Real-Time-Emergency-Alert-and-Broadcast-System.git
cd Real-Time-Emergency-Alert-and-Broadcast-System
mvn clean verify
```

## Branch workflow
Create a short branch for each task, for example:
- codex/server-connections
- codex/admin-alert-form
- codex/client-alert-display

```sh
git switch main
git pull --ff-only
git switch -c codex/server-connections
# Make changes, then:
git add server
git commit -m "Add server connection handling"
git push -u origin codex/server-connections
```

Open a pull request to main. Include what changed, how it was checked, and screenshots for interface changes. Ask another member to review. Merge after review and successful build.

## File ownership
Member 1 leads server/ and common/; Member 2 leads admin/; Member 3 leads client/.
Coordinate changes to common/, the root pom.xml, and protocol documentation before implementing them.
Any changed message format must update docs/PROTOCOL.md in the same pull request.

## Repository owner setup
In repository Settings, invite the two teammates as collaborators. They must accept.
If available for this repository/account, configure main to require pull requests and a successful Java build.
Do not commit passwords, tokens, generated databases, or IDE/build files.
Supply admin credentials through environment variables when authentication is implemented.

## LAN demo
Use localhost while developing on one computer. On other laptops, use the server laptop's LAN IPv4 address and the same configured port (proposed default: 5050). Allow the Java server through the firewall for the trusted demo network. Never disable the firewall globally.

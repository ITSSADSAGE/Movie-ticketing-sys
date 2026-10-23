# Reelhouse Movie Booking Demo

A small movie-ticket booking website built with HTML, CSS, JavaScript, and a Java server. Java serves the pages and API. The API uses SQLite to save movies, showtimes, temporary seat holds, and confirmed bookings.

## Run It on Localhost

You need a JDK installed. The SQLite JDBC libraries are already in `lib`, so Maven and a separate database installation are not needed.

1. Open the `PS-IP` project folder in VS Code.
2. Open **Terminal > New Terminal**.
3. Run the server batch file:

	```powershell
	.\run-server.bat
	```

4. Open this address in a browser: <http://localhost:8080>
5. Keep the terminal open while using the website. Press `Ctrl+C` in that terminal to stop the server.

Do not open `index.html` directly. The pages call the Java API, so they need to be served by the Java server.

The first run creates `db/reelhouse.db`, creates the database tables, and inserts the sample movies and shows. If the older `bookings.tsv` or `seat_holds.tsv` files exist, Java imports their records once. The old files are left in place as backups.

To use OMDb data, set your own key in the same PowerShell terminal before starting the server:

```powershell
$env:OMDB_API_KEY = "YOUR_KEY_HERE"
.\run-server.bat
```

The key is read by Java and is not stored in the website files or sent to the browser. Without a key, bundled poster, rating, and cast/crew details are used.

## Project Files

| File or folder | What it does |
| --- | --- |
| `index.html` | Home page and movie cards. |
| `movies.html` | Movie search and listing. |
| `movie-details.html` | Movie information, cast/crew, cinema, and showtimes. |
| `seats.html` | Shows the seat map and lets the user choose seats. |
| `booking.html` | Displays the booking summary and demo confirmation button. |
| `ticket.html` | Displays the confirmed ticket. |
| `bookings.html` | Shows bookings for the current demo browser session. |
| `css/style.css` | Shared page layout, colors, and responsive styling. |
| `js/app.js` | Loads page data with `fetch`, renders the pages, and sends seat/booking requests. It stores only the current selection and a demo session ID in the browser. |
| `server/Main.java` | Starts the web server, creates the database, handles API requests, checks seat availability, and calls OMDb. |
| `db/reelhouse.db` | SQLite database created automatically by Java. It is excluded from Git because it is local application data. |
| `lib/` | Java libraries needed to connect to SQLite. |
| `run-server.bat` | Adds the libraries to Java's class path and starts the server. |

## How the Java Database Works

The database is SQLite: a SQL database stored in the single file `db/reelhouse.db`. Java connects to it with JDBC, the standard Java interface for sending SQL commands to a database. The SQLite JDBC driver used here is [Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc), downloaded from [Maven Central](https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/). It lets the Java program use SQL without installing a separate database server. The small SLF4J jars in `lib` are dependencies used by that driver.

Java creates four main tables:

| Table | Information stored |
| --- | --- |
| `movies` | Movie title, description, poster, rating, cinema, and credit details. |
| `shows` | A movie's showtime and ticket price. |
| `seat_holds` | User/session ID, show ID, selected seats, and hold expiration time. |
| `bookings` | Confirmed booking ID, user/session ID, show, seats, and total. |

In `Main.java`, `initializeDatabase` creates tables, `seedDatabase` adds starter movies the first time, and `connect` opens a JDBC connection. API functions use simple SQL statements such as `SELECT`, `INSERT`, and `DELETE` to read and change the records.

## How Seat Holding Works

1. A browser gets a demo user ID and sends the selected show and seats to `POST /api/holds`.
2. Java checks SQLite for seats already booked or held by a different user. If any seat conflicts, Java returns an HTTP `409` error, and those seats cannot be selected.
3. If the seats are free, Java saves them in `seat_holds` with an expiration time five minutes in the future. The seat map then shows the hold to other browsers using this server.
4. On the booking page, Java checks that the hold still exists and belongs to that same user. Confirming inserts a row into `bookings` and removes the hold.
5. If the user leaves or takes too long, the expiration time passes. The next availability check ignores that expired hold, making those seats available again.

Hold creation and confirmation use a database transaction so the availability check and the write happen together. The server also synchronizes booking requests inside this one local Java process. This is a classroom demo, not a production reservation system.

## API Endpoints

The browser uses these routes on the same localhost server:

| Method and path | Purpose |
| --- | --- |
| `GET /api/movies` | Gets movies and showtimes from SQLite, with optional OMDb details. |
| `GET /api/seats?showId=...&userId=...` | Gets booked seats, active holds, and the current user's held seats. |
| `POST /api/holds` | Tries to hold a user's selected seats for five minutes. |
| `POST /api/bookings` | Confirms the booking if the user's hold is still valid. |
| `GET /api/bookings?userId=...` | Gets bookings associated with the current demo session. |

`app.js` calls these endpoints with JavaScript's `fetch` function. The Java server returns JSON for the page to display.

## Why OMDb Is Used

The app calls the [OMDb API](https://www.omdbapi.com/), a separate movie-information service, to look up posters, ratings, cast, director, writer, and IMDb title IDs. The API key is added to the request by Java on the server. It is not an IMDb website URL: IMDb review pages are linked to separately because OMDb does not provide review text. If the lookup fails, the app uses its bundled movie details instead.

## Booking and Payment Notes

The **Confirm demo payment** button simulates confirmation; there is no payment gateway connected and no money is collected. The browser's demo user ID is only used to separate example bookings; it is not a login or secure account.
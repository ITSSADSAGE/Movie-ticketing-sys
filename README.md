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

The first run creates `db/reelhouse.db` and its tables. Java adds any missing starter movies and shows on startup without replacing saved bookings. If the older `bookings.tsv` or `seat_holds.tsv` files exist, Java imports their records once. The old files are left in place as backups.

For real OMDb details and TMDb movie stills, set your own API keys in the same PowerShell terminal before starting the server:

```powershell
$env:OMDB_API_KEY = "YOUR_KEY_HERE"
$env:TMDB_READ_ACCESS_TOKEN = "YOUR_TMDB_READ_ACCESS_TOKEN"
.\run-server.bat
```

The credentials are read by Java and are not stored in the website files or sent to the browser. OMDb can provide posters, ratings, cast/crew, and plot details. TMDb's v4 Read Access Token is sent in an `Authorization: Bearer` header and supplies the extra backdrop images used by the movie photo gallery. A TMDb v3 API key can also be used with `TMDB_API_KEY`. If a credential is missing or rejected, the website uses its bundled movie details and provides an IMDb Photos link instead of the gallery.

The starter catalogue is a curated mix of 50 popular films, including Spider-Man, Transformers, Dangal, and Sonu Ke Titu Ki Sweety. It is not an official IMDb ranking. Java inserts any missing starter movies into SQLite without deleting existing bookings.

To show multiple movie stills in the detail page, register for TMDb API access at <https://www.themoviedb.org/settings/api> and set the v4 Read Access Token before starting Java:

```powershell
$env:TMDB_READ_ACCESS_TOKEN = "YOUR_TMDB_READ_ACCESS_TOKEN"
.\run-server.bat
```

The TMDb token stays on the Java server. The detail page requests backdrop images through `GET /api/photos?imdbId=...`; Java uses the IMDb ID to find the matching TMDb movie and returns its backdrop URLs. If no valid TMDb credential is set or no images are available, the page keeps its poster and links to the movie's IMDb Photos page. OMDb provides movie details and a poster, but it does not provide a photo gallery.

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
| `server/Main.java` | Starts the web server, creates the database, handles API requests, checks seat availability, and calls OMDb/TMDb. |
| `db/reelhouse.db` | SQLite database created automatically by Java. It is excluded from Git because it is local application data. |
| `lib/` | Java libraries needed to connect to SQLite. |
| `run-server.bat` | Adds the libraries to Java's class path and starts the server. |

## How the Movie Booking System Works

This is the complete path from selecting a film to getting a ticket. The browser displays pages and sends requests; Java handles each request, checks the rules, and reads or changes SQLite.

1. **Load movies:** `startPage` in `js/app.js` calls `GET /api/movies`. `handleApi` in Java routes the request to `movieListJson`, which reads movies and shows from SQLite. Java can also ask OMDb for poster, plot, rating, and credit information before returning JSON.
2. **Choose a movie and show:** Clicking a movie card opens `movie-details.html`. `renderDetails` displays the synopsis, cast, cinema, and showtimes. Selecting a show saves its ID and the movie ID in the browser's `localStorage`, then opens the seat page. This is temporary page state, not a booking.
3. **Check seats:** `renderSeats` calls `GET /api/seats` with the show ID and demo user ID. Java's `seatStatus` reads confirmed bookings and unexpired holds from SQLite, then returns the unavailable seats and any seats held by this user.
4. **Hold selected seats:** When a seat is clicked, `changeSeat` sends a form request to `POST /api/holds`, including the show ID, user ID, and selected seat numbers. `createHold` starts a database transaction and calls `hasConflict`. That method checks the `bookings` table and active holds belonging to other users. If a seat is taken, Java returns HTTP `409 Conflict`; otherwise, it saves the seats and a five-minute expiration time in `seat_holds`, then commits the transaction.
5. **Review or go back:** The booking summary calls `GET /api/seats` again to make sure the user's hold is still active. It shows the price and a countdown. **Back to seats** returns to the seat map without releasing the hold, so the user can change their selection while time remains.
6. **Confirm the booking:** Clicking **Confirm demo payment** sends the held seats to `POST /api/bookings`. `createBooking` checks the unexpired hold and checks for conflicts again. If everything is valid, one transaction inserts a row into `bookings` and deletes the temporary row from `seat_holds`. Java returns the booking ID and ticket details. If the hold expired, Java rejects the request and asks the user to select seats again.
7. **Show the ticket:** The browser opens `ticket.html` with the booking ID. `renderTicket` calls `GET /api/bookings` with that ID and the demo user ID. Java reads the saved record and returns the movie, show, seats, total, and booking ID. **My bookings** uses the same endpoint to list this demo user's bookings.

In short, movie selection stays in the browser until seats are requested. Holds and confirmed tickets are stored in SQLite by the Java server. The payment step is only a demo confirmation; it does not charge money.

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
| `GET /api/photos?imdbId=...` | Gets TMDb backdrops for one movie, when `TMDB_API_KEY` is set. |
| `GET /api/seats?showId=...&userId=...` | Gets booked seats, active holds, and the current user's held seats. |
| `POST /api/holds` | Tries to hold a user's selected seats for five minutes. |
| `POST /api/bookings` | Confirms the booking if the user's hold is still valid. |
| `GET /api/bookings?userId=...` | Gets bookings associated with the current demo session. |

`app.js` calls these endpoints with JavaScript's `fetch` function. The Java server returns JSON for the page to display.

## Why OMDb Is Used

The app calls the [OMDb API](https://www.omdbapi.com/), a separate movie-information service, to look up posters, ratings, plots, cast, director, writer, and IMDb title IDs. The API key is added to the request by Java on the server. It is not an IMDb website URL: IMDb review pages are linked to separately because OMDb does not provide review text. If the lookup fails, the app uses its bundled movie details instead.

For the movie photo grid, Java optionally calls the [TMDb API](https://developer.themoviedb.org/) using the movie's IMDb ID, then returns backdrop image URLs to the page. OMDb does not provide multiple film stills. If `TMDB_API_KEY` is not set or no matching images are found, the movie poster and IMDb Photos link remain available.

## Booking and Payment Notes

The **Confirm demo payment** button simulates confirmation; there is no payment gateway connected and no money is collected. The browser's demo user ID is only used to separate example bookings; it is not a login or secure account.
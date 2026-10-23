# Movie Ticket Booking Demo

A movie booking demo built with **HTML, CSS, vanilla JavaScript, and a Java HTTP server**. Movie/show data, seat holds, and confirmed bookings are stored in a local SQLite database through Java JDBC. The browser stores only the current selection and a demo session ID.

## Project Structure

```text
PS-IP/
├── index.html              # Home page
├── movies.html             # Movie listing and search
├── movie-details.html      # Movie and show selection
├── seats.html              # Seat selection
├── booking.html            # Booking summary and confirmation
├── ticket.html             # Ticket view
├── bookings.html           # Booking history
├── css/
│   └── style.css           # Shared responsive styles
├── js/
│   └── app.js              # Page rendering and booking flow
├── server/
│   └── Main.java           # HTTP API and simple JDBC queries
├── lib/
│   ├── sqlite-jdbc.jar     # SQLite database driver
│   ├── slf4j-api.jar       # Driver logging dependency
│   └── slf4j-nop.jar       # Keeps driver logging quiet
└── run-server.bat          # Starts the Java server
```

## Booking Flow

1. Browse or search the movie catalogue returned by the Java API.
2. Choose a movie show.
3. Select seats; the server holds them for five minutes and rejects overlapping holds.
4. Review the total and confirm the demo booking before the hold expires.
5. View the server-stored ticket or find it later under **My bookings**.

An active hold expires automatically after five minutes. Confirmed seats remain unavailable for that show. All API state is shared by browser sessions using the same running server.

## Run Locally

Run `run-server.bat`, then open `http://localhost:8080`. The script compiles the Java source with the JDBC driver on its class path. On first run, Java creates `db/reelhouse.db`, creates its tables, and inserts the sample movies and shows. It also imports old `bookings.tsv` and `seat_holds.tsv` records once; those files remain as backups. Do not open the HTML files directly; the API must be running.

## Customize the Demo

Edit the movie catalogue in `server/Main.java` to change movie titles, descriptions, theatres, dates, showtimes, and prices. Shared page behavior is in `js/app.js`, and the visual theme is in `css/style.css`.

## IMDb Metadata

The server optionally looks up posters, IMDb ratings, and IMDb title IDs through OMDb. Set the `OMDB_API_KEY` environment variable before starting the server. The key is never sent to the browser. Without a key or when a lookup fails, the app uses its bundled poster artwork and sample ratings. IMDb user-review text is not provided by OMDb; when a title has a matching IMDb ID, the app links to its IMDb reviews page instead.

## Scope

Payment confirmation is simulated; no payment gateway is connected. The browser-generated session ID distinguishes demo users but is not authentication. This local SQLite setup is intended for one Java server process and classroom demonstration, not production or multi-server deployment.
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final long HOLD_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();
    private static final Path DATABASE = Path.of(System.getenv().getOrDefault("REELHOUSE_DB_DIR", ROOT.resolve("db").toString())).toAbsolutePath().normalize();
    private static final Path DATABASE_FILE = DATABASE.resolve("reelhouse.db");
    private static final Path HOLDS_FILE = DATABASE.resolve("seat_holds.tsv");
    private static final Path BOOKINGS_FILE = DATABASE.resolve("bookings.tsv");
    private static final Object DATABASE_LOCK = new Object();
    private static final String OMDB_KEY = System.getenv("OMDB_API_KEY");
    private static final String TMDB_API_KEY = System.getenv("TMDB_API_KEY");
    private static final String TMDB_READ_ACCESS_TOKEN = System.getenv("TMDB_READ_ACCESS_TOKEN");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final List<Movie> MOVIES = movies();
    private static final Map<String, Map<String, String>> OMDB_CACHE = new HashMap<>();
    private static final Map<String, List<String>> PHOTO_CACHE = new HashMap<>();

    private static class Show {
        String id;
        String time;
        int price;

        Show(String id, String time, int price) {
            this.id = id;
            this.time = time;
            this.price = price;
        }
    }

    private static class Movie {
        String id;
        String title;
        String genre;
        String language;
        String duration;
        String rating;
        String accent;
        String synopsis;
        String theatre;
        String date;
          String posterUrl;
          String imdbId;
          String actors;
          String director;
          String writer;
        Show[] shows;

        Movie(String id, String title, String genre, String language, String duration,
              String rating, String accent, String synopsis, String theatre, String date, String posterUrl,
              String imdbId, String actors, String director, String writer, Show[] shows) {
            this.id = id;
            this.title = title;
            this.genre = genre;
            this.language = language;
            this.duration = duration;
            this.rating = rating;
            this.accent = accent;
            this.synopsis = synopsis;
            this.theatre = theatre;
            this.date = date;
            this.posterUrl = posterUrl;
            this.imdbId = imdbId;
            this.actors = actors;
            this.director = director;
            this.writer = writer;
            this.shows = shows;
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        try {
            initializeDatabase();
        } catch (SQLException exception) {
            throw new IOException("Could not open the SQLite database", exception);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", Main::handleApi);
        server.createContext("/", Main::handleStatic);
        server.start();
        System.out.println("Reelhouse running at http://localhost:" + port);
        if (OMDB_KEY == null || OMDB_KEY.isBlank()) {
            System.out.println("OMDb disabled; using bundled poster/rating fallbacks. Set OMDB_API_KEY to enable IMDb lookups.");
        }
    }

    private static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_FILE);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private static void initializeDatabase() throws IOException, SQLException {
        Files.createDirectories(DATABASE);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("CREATE TABLE IF NOT EXISTS movies (id TEXT PRIMARY KEY, title TEXT, genre TEXT, language TEXT, duration TEXT, rating TEXT, accent TEXT, synopsis TEXT, theatre TEXT, date TEXT, poster_url TEXT, imdb_id TEXT, actors TEXT, director TEXT, writer TEXT)");
            statement.execute("CREATE TABLE IF NOT EXISTS shows (id TEXT PRIMARY KEY, movie_id TEXT, time TEXT, price INTEGER, FOREIGN KEY(movie_id) REFERENCES movies(id))");
            statement.execute("CREATE TABLE IF NOT EXISTS seat_holds (user_id TEXT, show_id TEXT, seats TEXT, expires_at INTEGER, PRIMARY KEY(user_id, show_id), FOREIGN KEY(show_id) REFERENCES shows(id))");
            statement.execute("CREATE TABLE IF NOT EXISTS bookings (id TEXT PRIMARY KEY, user_id TEXT, show_id TEXT, seats TEXT, total INTEGER, created_at INTEGER, FOREIGN KEY(show_id) REFERENCES shows(id))");
            statement.execute("CREATE TABLE IF NOT EXISTS migrations (name TEXT PRIMARY KEY)");
            seedDatabase(connection);
            migrateOldFiles(connection);
        }
    }

    private static void seedDatabase(Connection connection) throws SQLException {
        String movieSql = "INSERT OR IGNORE INTO movies VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String showSql = "INSERT OR IGNORE INTO shows VALUES (?, ?, ?, ?)";
        try (PreparedStatement movieInsert = connection.prepareStatement(movieSql);
             PreparedStatement showInsert = connection.prepareStatement(showSql)) {
            for (Movie movie : MOVIES) {
                movieInsert.setString(1, movie.id);
                movieInsert.setString(2, movie.title);
                movieInsert.setString(3, movie.genre);
                movieInsert.setString(4, movie.language);
                movieInsert.setString(5, movie.duration);
                movieInsert.setString(6, movie.rating);
                movieInsert.setString(7, movie.accent);
                movieInsert.setString(8, movie.synopsis);
                movieInsert.setString(9, movie.theatre);
                movieInsert.setString(10, movie.date);
                movieInsert.setString(11, movie.posterUrl);
                movieInsert.setString(12, movie.imdbId);
                movieInsert.setString(13, movie.actors);
                movieInsert.setString(14, movie.director);
                movieInsert.setString(15, movie.writer);
                movieInsert.executeUpdate();
                for (Show show : movie.shows) {
                    showInsert.setString(1, show.id);
                    showInsert.setString(2, movie.id);
                    showInsert.setString(3, show.time);
                    showInsert.setInt(4, show.price);
                    showInsert.executeUpdate();
                }
            }
        }
    }

    private static void migrateOldFiles(Connection connection) throws IOException, SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT name FROM migrations WHERE name = 'file-store-v1'")) {
            if (result.next()) return;
        }

        List<String> oldBookings = readRows(BOOKINGS_FILE);
        try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO bookings VALUES (?, ?, ?, ?, ?, ?)")) {
            for (String row : oldBookings) {
                String[] fields = row.split("\t", -1);
                if (fields.length != 6) continue;
                insert.setString(1, fields[0]);
                insert.setString(2, fields[1]);
                insert.setString(3, fields[2]);
                insert.setString(4, fields[3]);
                insert.setInt(5, Integer.parseInt(fields[4]));
                insert.setLong(6, Long.parseLong(fields[5]));
                insert.executeUpdate();
            }
        }

        List<String> oldHolds = readRows(HOLDS_FILE);
        try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO seat_holds VALUES (?, ?, ?, ?)")) {
            for (String row : oldHolds) {
                String[] fields = row.split("\t", -1);
                if (fields.length != 4) continue;
                insert.setString(1, fields[0]);
                insert.setString(2, fields[1]);
                insert.setString(3, fields[2]);
                insert.setLong(4, Long.parseLong(fields[3]));
                insert.executeUpdate();
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT OR IGNORE INTO migrations VALUES ('file-store-v1')");
        }
    }

    private static List<Movie> movies() {
        List<Movie> movies = new ArrayList<>();
        movies.add(new Movie("orbit", "Interstellar", "Science fiction · Adventure", "English", "2h 49m", "8.7", "orbit",
            "As Earth becomes harder to live on, former pilot Cooper joins a mission through a wormhole to search for a new home for humanity.", "Reelhouse Central", "2026-10-05",
            "https://upload.wikimedia.org/wikipedia/en/b/bc/Interstellar_film_poster.jpg", "tt0816692",
            "Matthew McConaughey, Anne Hathaway, Jessica Chastain", "Christopher Nolan", "Jonathan Nolan, Christopher Nolan",
            new Show[] { new Show("orbit-1430", "2:30 PM", 250), new Show("orbit-1830", "6:30 PM", 280), new Show("orbit-2115", "9:15 PM", 220) }));
        movies.add(new Movie("blue-hour", "La La Land", "Romance · Musical · Drama", "English", "2h 08m", "8.0", "blue-hour",
            "An aspiring actor and a jazz pianist pursue their careers and a relationship in Los Angeles, where ambition and love do not always follow the same path.", "Crescent Multiplex", "2026-10-05",
            "https://upload.wikimedia.org/wikipedia/en/a/ab/La_La_Land_%28film%29.png", "tt3783958",
            "Ryan Gosling, Emma Stone, John Legend", "Damien Chazelle", "Damien Chazelle",
            new Show[] { new Show("blue-1600", "4:00 PM", 210), new Show("blue-1945", "7:45 PM", 240) }));
        movies.add(new Movie("wildwood", "Dangal", "Biography · Sport · Drama", "Hindi", "2h 41m", "8.3", "wildwood",
            "A former wrestler trains his daughters to compete in wrestling, challenging expectations in their town and within their family.", "Reelhouse Riverside", "2026-10-06",
            "https://upload.wikimedia.org/wikipedia/en/9/99/Dangal_Poster.jpg", "tt5074352",
            "Aamir Khan, Sakshi Tanwar, Fatima Sana Shaikh", "Nitesh Tiwari", "Piyush Gupta, Shreyas Jain, Nitesh Tiwari",
            new Show[] { new Show("wild-1300", "1:00 PM", 230), new Show("wild-1745", "5:45 PM", 260) }));
        addPopularMovies(movies);
        return movies;
    }

    private static void addPopularMovies(List<Movie> movies) {
        String[][] titles = {
            { "Spider-Man", "Action · Adventure", "English" },
            { "Spider-Man 2", "Action · Adventure", "English" },
            { "The Amazing Spider-Man", "Action · Adventure", "English" },
            { "Spider-Man: No Way Home", "Action · Adventure", "English" },
            { "Transformers", "Action · Science fiction", "English" },
            { "Transformers: Dark of the Moon", "Action · Science fiction", "English" },
            { "The Dark Knight", "Action · Crime", "English" },
            { "Inception", "Science fiction · Thriller", "English" },
            { "The Shawshank Redemption", "Drama", "English" },
            { "The Godfather", "Crime · Drama", "English" },
            { "The Godfather Part II", "Crime · Drama", "English" },
            { "Avengers: Endgame", "Action · Adventure", "English" },
            { "The Avengers", "Action · Adventure", "English" },
            { "Black Panther", "Action · Adventure", "English" },
            { "Iron Man", "Action · Science fiction", "English" },
            { "Titanic", "Drama · Romance", "English" },
            { "Avatar", "Action · Science fiction", "English" },
            { "Jurassic Park", "Adventure · Science fiction", "English" },
            { "The Matrix", "Action · Science fiction", "English" },
            { "The Lord of the Rings: The Fellowship of the Ring", "Adventure · Fantasy", "English" },
            { "The Lord of the Rings: The Return of the King", "Adventure · Fantasy", "English" },
            { "Harry Potter and the Sorcerer's Stone", "Adventure · Fantasy", "English" },
            { "The Lion King", "Animation · Adventure", "English" },
            { "Coco", "Animation · Adventure", "English" },
            { "Inside Out", "Animation · Adventure", "English" },
            { "Oppenheimer", "Biography · Drama", "English" },
            { "Barbie", "Comedy · Adventure", "English" },
            { "Top Gun: Maverick", "Action · Drama", "English" },
            { "Gladiator", "Action · Drama", "English" },
            { "Joker", "Crime · Drama", "English" },
            { "Parasite", "Drama · Thriller", "Korean" },
            { "3 Idiots", "Comedy · Drama", "Hindi" },
            { "PK", "Comedy · Drama", "Hindi" },
            { "Taare Zameen Par", "Drama · Family", "Hindi" },
            { "Lagaan", "Drama · Sport", "Hindi" },
            { "Chak De! India", "Drama · Sport", "Hindi" },
            { "Andhadhun", "Crime · Thriller", "Hindi" },
            { "Drishyam", "Crime · Thriller", "Hindi" },
            { "K.G.F: Chapter 2", "Action · Crime", "Kannada" },
            { "RRR", "Action · Drama", "Telugu" },
            { "Jawan", "Action · Thriller", "Hindi" },
            { "Pathaan", "Action · Thriller", "Hindi" },
            { "Bajrangi Bhaijaan", "Comedy · Drama", "Hindi" },
            { "Queen", "Comedy · Drama", "Hindi" },
            { "Tumbbad", "Drama · Fantasy", "Hindi" },
            { "Sonu Ke Titu Ki Sweety", "Comedy · Romance", "Hindi" },
            { "Baahubali: The Beginning", "Action · Drama", "Telugu" }
        };

        String showDate = LocalDate.now().plusDays(1).toString();
        for (int i = 0; i < titles.length; i++) {
            String id = "popular-" + (i + 4);
            Show show = new Show(id + "-evening", "7:00 PM", 250);
            Movie movie = new Movie(id, titles[i][0], titles[i][1], titles[i][2], "", "N/A", "orbit",
                "Movie details and cast are provided by OMDb when available.", "Reelhouse Central", showDate,
                "", "", "", "", "", new Show[] { show });
            movies.add(movie);
        }
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, String> query = parsePairs(exchange.getRequestURI().getRawQuery());
            if (path.equals("/api/movies") && method.equals("GET")) {
                json(exchange, 200, movieListJson());
                return;
            }
            if (path.equals("/api/photos") && method.equals("GET")) {
                json(exchange, 200, moviePhotosJson(query.get("imdbId")));
                return;
            }
            if (path.equals("/api/seats") && method.equals("GET")) {
                synchronized (DATABASE_LOCK) {
                    json(exchange, 200, seatStatus(query.get("showId"), query.get("userId")));
                }
                return;
            }
            if (path.equals("/api/holds") && method.equals("POST")) {
                Map<String, String> form = parsePairs(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                synchronized (DATABASE_LOCK) {
                    createHold(exchange, form);
                }
                return;
            }
            if (path.equals("/api/bookings") && method.equals("POST")) {
                Map<String, String> form = parsePairs(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                synchronized (DATABASE_LOCK) {
                    createBooking(exchange, form);
                }
                return;
            }
            if (path.equals("/api/bookings") && method.equals("GET")) {
                synchronized (DATABASE_LOCK) {
                    json(exchange, 200, bookingsJson(query.get("userId"), query.get("id")));
                }
                return;
            }
            json(exchange, 404, errorJson("API route not found"));
        } catch (IllegalArgumentException exception) {
            json(exchange, 400, errorJson(exception.getMessage()));
        } catch (SQLException exception) {
            json(exchange, 500, errorJson("Database operation failed"));
        } catch (IOException exception) {
            json(exchange, 500, errorJson("Database operation failed"));
        } finally {
            exchange.close();
        }
    }

    private static void createHold(HttpExchange exchange, Map<String, String> form) throws SQLException, IOException {
        String showId = required(form, "showId");
        String userId = validUserId(required(form, "userId"));
        findShow(showId);
        List<String> seats = splitSeats(form.getOrDefault("seats", ""));
        for (int i = 0; i < seats.size(); i++) {
            if (!seats.get(i).matches("[A-D][1-8]")) {
                throw new IllegalArgumentException("One or more seats are invalid");
            }
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + HOLD_MILLIS;
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            if (hasConflict(connection, showId, seats, userId, now)) {
                connection.rollback();
                json(exchange, 409, errorJson("Some seats were just taken. Refresh the seat map and try again."));
                return;
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM seat_holds WHERE user_id = ? AND show_id = ?")) {
                delete.setString(1, userId);
                delete.setString(2, showId);
                delete.executeUpdate();
            }
            if (!seats.isEmpty()) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO seat_holds VALUES (?, ?, ?, ?)")) {
                    insert.setString(1, userId);
                    insert.setString(2, showId);
                    insert.setString(3, String.join(",", seats));
                    insert.setLong(4, expiresAt);
                    insert.executeUpdate();
                }
            }
            connection.commit();
        }
        json(exchange, 200, "{\"expiresAt\":" + (seats.isEmpty() ? 0 : expiresAt) + "}");
    }

    private static void createBooking(HttpExchange exchange, Map<String, String> form) throws SQLException, IOException {
        String showId = required(form, "showId");
        String userId = validUserId(required(form, "userId"));
        Show show = findShow(showId);
        Movie movie = movieForShow(showId);
        List<String> seats = splitSeats(form.getOrDefault("seats", ""));
        if (seats.isEmpty()) throw new IllegalArgumentException("Select at least one seat");

        long now = System.currentTimeMillis();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            String heldSeats = "";
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT seats FROM seat_holds WHERE user_id = ? AND show_id = ? AND expires_at > ?")) {
                query.setString(1, userId);
                query.setString(2, showId);
                query.setLong(3, now);
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) heldSeats = result.getString("seats");
                }
            }
            if (!splitSeats(heldSeats).containsAll(seats)) {
                connection.rollback();
                json(exchange, 409, errorJson("Your seat hold expired. Please select your seats again."));
                return;
            }
            if (hasConflict(connection, showId, seats, userId, now)) {
                connection.rollback();
                json(exchange, 409, errorJson("A selected seat is no longer available."));
                return;
            }

            String id = "RH" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            int total = seats.size() * show.price + 30;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO bookings VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, id);
                insert.setString(2, userId);
                insert.setString(3, showId);
                insert.setString(4, String.join(",", seats));
                insert.setInt(5, total);
                insert.setLong(6, now);
                insert.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM seat_holds WHERE user_id = ? AND show_id = ?")) {
                delete.setString(1, userId);
                delete.setString(2, showId);
                delete.executeUpdate();
            }
            connection.commit();
            json(exchange, 201, bookingJson(id, userId, showId, seats, total, now, movie, show));
        }
    }

    private static String seatStatus(String showId, String userId) throws SQLException {
        findShow(showId);
        String safeUser = userId == null ? "" : userId;
        long now = System.currentTimeMillis();
        List<String> blocked = new ArrayList<>();
        List<String> ownSeats = new ArrayList<>();
        long expiresAt = 0;
        try (Connection connection = connect()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT seats FROM bookings WHERE show_id = ?")) {
                query.setString(1, showId);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) blocked.addAll(splitSeats(result.getString("seats")));
                }
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT user_id, seats, expires_at FROM seat_holds WHERE show_id = ? AND expires_at > ?")) {
                query.setString(1, showId);
                query.setLong(2, now);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        List<String> heldSeats = splitSeats(result.getString("seats"));
                        if (result.getString("user_id").equals(safeUser)) {
                            ownSeats.addAll(heldSeats);
                            expiresAt = Math.max(expiresAt, result.getLong("expires_at"));
                        } else {
                            blocked.addAll(heldSeats);
                        }
                    }
                }
            }
        }
        return "{\"blocked\":" + stringArray(blocked) + ",\"ownSeats\":" + stringArray(ownSeats)
            + ",\"expiresAt\":" + expiresAt + "}";
    }

    private static boolean hasConflict(Connection connection, String showId, List<String> seats,
                                       String userId, long now) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT seats FROM bookings WHERE show_id = ?")) {
            query.setString(1, showId);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    for (String seat : splitSeats(result.getString("seats"))) {
                        if (seats.contains(seat)) return true;
                    }
                }
            }
        }

        try (PreparedStatement query = connection.prepareStatement(
                "SELECT seats FROM seat_holds WHERE show_id = ? AND user_id != ? AND expires_at > ?")) {
            query.setString(1, showId);
            query.setString(2, userId);
            query.setLong(3, now);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    for (String seat : splitSeats(result.getString("seats"))) {
                        if (seats.contains(seat)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static String bookingsJson(String userId, String bookingId) throws SQLException {
        List<String> result = new ArrayList<>();
        if (userId == null || userId.isBlank()) return "[]";
        validUserId(userId);
        String sql = "SELECT id, show_id, seats, total, created_at FROM bookings WHERE user_id = ?";
        if (bookingId != null) sql += " AND id = ?";
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, userId);
            if (bookingId != null) query.setString(2, bookingId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String showId = rows.getString("show_id");
                    Movie movie = movieForShow(connection, showId);
                    Show show = findShow(connection, showId);
                    result.add(bookingJson(rows.getString("id"), userId, showId,
                        splitSeats(rows.getString("seats")), rows.getInt("total"),
                        rows.getLong("created_at"), movie, show));
                }
            }
        }
        return "[" + String.join(",", result) + "]";
    }

    private static String bookingJson(String id, String userId, String showId, List<String> seats, int total,
                                      long createdAt, Movie movie, Show show) {
        return "{\"id\":" + quote(id) + ",\"userId\":" + quote(userId) + ",\"movieId\":" + quote(movie.id)
            + ",\"showId\":" + quote(showId) + ",\"seats\":" + stringArray(seats)
            + ",\"movieTitle\":" + quote(movie.title) + ",\"theatre\":" + quote(movie.theatre)
            + ",\"date\":" + quote(movie.date) + ",\"time\":" + quote(show.time)
            + ",\"total\":" + total + ",\"createdAt\":" + createdAt + "}";
    }

    private static String movieListJson() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM movies ORDER BY rowid"); ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String movieId = rows.getString("id");
                List<Show> movieShows = new ArrayList<>();
                try (PreparedStatement showQuery = connection.prepareStatement(
                        "SELECT id, time, price FROM shows WHERE movie_id = ? ORDER BY rowid")) {
                    showQuery.setString(1, movieId);
                    try (ResultSet showRows = showQuery.executeQuery()) {
                        while (showRows.next()) {
                            movieShows.add(new Show(showRows.getString("id"), showRows.getString("time"),
                                showRows.getInt("price")));
                        }
                    }
                }

                Movie movie = new Movie(movieId, rows.getString("title"), rows.getString("genre"),
                    rows.getString("language"), rows.getString("duration"), rows.getString("rating"),
                    rows.getString("accent"), rows.getString("synopsis"), rows.getString("theatre"),
                    rows.getString("date"), rows.getString("poster_url"), rows.getString("imdb_id"),
                    rows.getString("actors"), rows.getString("director"), rows.getString("writer"),
                    movieShows.toArray(new Show[0]));
            Map<String, String> metadata = imdbMetadata(movie);
            String rating = movie.rating;
            String ratingSource = movie.rating.equals("N/A") ? "No rating" : "IMDb";
            String poster = movie.posterUrl;
            String imdbId = movie.imdbId;
            String actors = movie.actors;
            String director = movie.director;
            String writer = movie.writer;
            String synopsis = movie.synopsis;
            if (metadata.containsKey("imdbRating") && !metadata.get("imdbRating").equals("N/A")) {
                rating = metadata.get("imdbRating");
                ratingSource = "IMDb";
            }
            if (metadata.containsKey("Poster") && !metadata.get("Poster").equals("N/A")) poster = metadata.get("Poster");
            if (metadata.containsKey("imdbID")) imdbId = metadata.get("imdbID");
            if (metadata.containsKey("Actors")) actors = metadata.get("Actors");
            if (metadata.containsKey("Director")) director = metadata.get("Director");
            if (metadata.containsKey("Writer")) writer = metadata.get("Writer");
            if (metadata.containsKey("Plot") && !metadata.get("Plot").equals("N/A")) synopsis = metadata.get("Plot");
            String reviews = imdbId.isBlank() || imdbId.equals("N/A") ? "" : "https://www.imdb.com/title/" + imdbId + "/reviews/";
            String shows = "";
                for (int j = 0; j < movie.shows.length; j++) {
                    Show show = movie.shows[j];
                    if (j > 0) shows += ",";
                shows += "{\"id\":" + quote(show.id) + ",\"time\":" + quote(show.time)
                    + ",\"price\":" + show.price + "}";
            }
                result.add("{\"id\":" + quote(movie.id) + ",\"title\":" + quote(movie.title)
                + ",\"genre\":" + quote(movie.genre) + ",\"language\":" + quote(movie.language)
                + ",\"duration\":" + quote(movie.duration) + ",\"rating\":" + quote(rating)
                + ",\"ratingSource\":" + quote(ratingSource) + ",\"accent\":" + quote(movie.accent)
                + ",\"posterUrl\":" + quote(poster) + ",\"actors\":" + quote(actors)
                + ",\"director\":" + quote(director) + ",\"writer\":" + quote(writer)
                + ",\"imdbId\":" + quote(imdbId) + ",\"reviewsUrl\":" + quote(reviews)
                + ",\"synopsis\":" + quote(synopsis) + ",\"theatre\":" + quote(movie.theatre)
                    + ",\"date\":" + quote(movie.date) + ",\"shows\":[" + shows + "]}");
                }
        }
            return "[" + String.join(",", result) + "]";
    }

    private static synchronized Map<String, String> imdbMetadata(Movie movie) {
        if (OMDB_KEY == null || OMDB_KEY.isBlank()) return new HashMap<>();
        if (OMDB_CACHE.containsKey(movie.id)) return OMDB_CACHE.get(movie.id);
        Map<String, String> fields = new HashMap<>();
        try {
            String url = "https://www.omdbapi.com/?apikey=" + URLEncoder.encode(OMDB_KEY, StandardCharsets.UTF_8)
                + "&t=" + URLEncoder.encode(movie.title, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
            String response = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
            String poster = readJsonString(response, "Poster");
            String rating = readJsonString(response, "imdbRating");
            String imdbId = readJsonString(response, "imdbID");
            String actors = readJsonString(response, "Actors");
            String director = readJsonString(response, "Director");
            String writer = readJsonString(response, "Writer");
            String plot = readJsonString(response, "Plot");
            if (!poster.isEmpty()) fields.put("Poster", poster);
            if (!rating.isEmpty()) fields.put("imdbRating", rating);
            if (!imdbId.isEmpty()) fields.put("imdbID", imdbId);
            if (!actors.isEmpty() && !actors.equals("N/A")) fields.put("Actors", actors);
            if (!director.isEmpty() && !director.equals("N/A")) fields.put("Director", director);
            if (!writer.isEmpty() && !writer.equals("N/A")) fields.put("Writer", writer);
            if (!plot.isEmpty() && !plot.equals("N/A")) fields.put("Plot", plot);
        } catch (Exception exception) {
            fields.clear();
        }
        OMDB_CACHE.put(movie.id, fields);
        return fields;
    }

    private static synchronized String moviePhotosJson(String imdbId) {
        if (imdbId == null || !imdbId.matches("tt[0-9]+")) return "[]";
        if (PHOTO_CACHE.containsKey(imdbId)) return stringArray(PHOTO_CACHE.get(imdbId));
        List<String> photos = new ArrayList<>();
        if ((TMDB_API_KEY == null || TMDB_API_KEY.isBlank())
            && (TMDB_READ_ACCESS_TOKEN == null || TMDB_READ_ACCESS_TOKEN.isBlank())) {
            PHOTO_CACHE.put(imdbId, photos);
            return "[]";
        }

        try {
            String findUrl = "https://api.themoviedb.org/3/find/" + imdbId
                + "?external_source=imdb_id";
            String findResponse = getTmdbText(findUrl);
            String movieId = readJsonNumber(findResponse, "id");
            if (!movieId.isEmpty()) {
                String imageUrl = "https://api.themoviedb.org/3/movie/" + movieId + "/images";
                String imageResponse = getTmdbText(imageUrl);
                int start = imageResponse.indexOf("\"backdrops\"");
                int end = imageResponse.indexOf("\"posters\"", start);
                if (start >= 0) {
                    String backdrops = end > start ? imageResponse.substring(start, end) : imageResponse.substring(start);
                    Matcher matcher = Pattern.compile("\\\"file_path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(backdrops);
                    while (matcher.find() && photos.size() < 8) {
                        photos.add("https://image.tmdb.org/t/p/w780" + matcher.group(1));
                    }
                }
            }
        } catch (Exception exception) {
            photos.clear();
        }
        PHOTO_CACHE.put(imdbId, photos);
        return stringArray(photos);
    }

    private static String getTmdbText(String url) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET();
        if (TMDB_READ_ACCESS_TOKEN != null && !TMDB_READ_ACCESS_TOKEN.isBlank()) {
            request.header("Authorization", "Bearer " + TMDB_READ_ACCESS_TOKEN);
        } else {
            String separator = url.contains("?") ? "&" : "?";
            String key = URLEncoder.encode(TMDB_API_KEY, StandardCharsets.UTF_8);
            request.uri(URI.create(url + separator + "api_key=" + key));
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) throw new IOException("TMDb request failed");
        return response.body();
    }

    private static String getText(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String readJsonNumber(String json, String field) {
        String search = "\"" + field + "\"";
        int fieldStart = json.indexOf(search);
        if (fieldStart < 0) return "";
        int position = json.indexOf(":", fieldStart + search.length()) + 1;
        while (position < json.length() && json.charAt(position) == ' ') position++;
        int end = position;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return end == position ? "" : json.substring(position, end);
    }

    private static String readJsonString(String json, String field) {
        String search = "\"" + field + "\"";
        int fieldStart = json.indexOf(search);
        if (fieldStart == -1) return "";
        int colon = json.indexOf(":", fieldStart + search.length());
        int quote = json.indexOf("\"", colon + 1);
        if (colon == -1 || quote == -1) return "";

        String value = "";
        boolean escaped = false;
        for (int i = quote + 1; i < json.length(); i++) {
            char character = json.charAt(i);
            if (escaped) {
                if (character == 'n' || character == 'r') value += " ";
                else value += character;
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                return value;
            } else {
                value += character;
            }
        }
        return "";
    }

    private static void handleStatic(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            Path file = ROOT.resolve("." + path).normalize();
            if (!file.startsWith(ROOT) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String name = file.getFileName().toString();
            String extension = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
            exchange.getResponseHeaders().set("Content-Type", contentType(extension));
            byte[] body = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private static Show findShow(String showId) throws SQLException {
        try (Connection connection = connect()) {
            return findShow(connection, showId);
        }
    }

    private static Show findShow(Connection connection, String showId) throws SQLException {
        if (showId == null) throw new IllegalArgumentException("A show is required");
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id, time, price FROM shows WHERE id = ?")) {
            query.setString(1, showId);
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    return new Show(result.getString("id"), result.getString("time"), result.getInt("price"));
                }
            }
        }
        throw new IllegalArgumentException("Show not found");
    }

    private static Movie movieForShow(String showId) throws SQLException {
        try (Connection connection = connect()) {
            return movieForShow(connection, showId);
        }
    }

    private static Movie movieForShow(Connection connection, String showId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT movies.id, title, genre, language, duration, rating, accent, synopsis, theatre, date, poster_url, imdb_id, actors, director, writer "
                    + "FROM movies JOIN shows ON movies.id = shows.movie_id WHERE shows.id = ?")) {
            query.setString(1, showId);
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    return new Movie(result.getString("id"), result.getString("title"), result.getString("genre"),
                        result.getString("language"), result.getString("duration"), result.getString("rating"),
                        result.getString("accent"), result.getString("synopsis"), result.getString("theatre"),
                        result.getString("date"), result.getString("poster_url"), result.getString("imdb_id"),
                        result.getString("actors"), result.getString("director"), result.getString("writer"), new Show[0]);
            }
            }
        }
        throw new IllegalArgumentException("Show not found");
    }

    private static String contentType(String extension) {
        if (extension.equals(".html")) return "text/html; charset=utf-8";
        if (extension.equals(".css")) return "text/css; charset=utf-8";
        if (extension.equals(".js")) return "text/javascript; charset=utf-8";
        if (extension.equals(".png")) return "image/png";
        if (extension.equals(".jpg") || extension.equals(".jpeg")) return "image/jpeg";
        if (extension.equals(".svg")) return "image/svg+xml";
        if (extension.equals(".md")) return "text/markdown; charset=utf-8";
        return "application/octet-stream";
    }

    private static List<String> readRows(Path file) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    private static List<String> splitSeats(String value) {
        List<String> seats = new ArrayList<>();
        if (value == null || value.isBlank()) return seats;
        String[] values = value.split(",");
        for (int i = 0; i < values.length; i++) {
            if (!seats.contains(values[i])) seats.add(values[i]);
        }
        return seats;
    }

    private static String required(Map<String, String> form, String field) {
        String value = form.get(field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private static String validUserId(String userId) {
        if (userId.length() == 0 || userId.length() > 80) {
            throw new IllegalArgumentException("Invalid user ID");
        }
        for (int i = 0; i < userId.length(); i++) {
            char character = userId.charAt(i);
            boolean valid = Character.isLetterOrDigit(character) || character == '-' || character == '_';
            if (!valid) throw new IllegalArgumentException("Invalid user ID");
        }
        return userId;
    }

    private static Map<String, String> parsePairs(String source) {
        Map<String, String> pairs = new LinkedHashMap<>();
        if (source == null || source.isBlank()) return pairs;
        for (String pair : source.split("&")) {
            String[] values = pair.split("=", 2);
            String key = URLDecoder.decode(values[0], StandardCharsets.UTF_8);
            String value = values.length == 2 ? URLDecoder.decode(values[1], StandardCharsets.UTF_8) : "";
            pairs.put(key, value);
        }
        return pairs;
    }

    private static String quote(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static String stringArray(List<String> values) {
        String result = "[";
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) result += ",";
            result += quote(values.get(i));
        }
        return result + "]";
    }

    private static String errorJson(String message) {
        return "{\"error\":" + quote(message == null ? "Request failed" : message) + "}";
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
var app = document.querySelector("#app");
var page = document.body.dataset.page;
var movies = [];
var selectionKey = "reelhouseSelection";
var userIdKey = "reelhouseUserId";

function readStorage(key, fallback) {
  try {
    var value = localStorage.getItem(key);
    if (value === null) return fallback;
    return JSON.parse(value);
  } catch (error) {
    return fallback;
  }
}

function saveStorage(key, value) {
  localStorage.setItem(key, JSON.stringify(value));
}

function getSelection() {
  return readStorage(selectionKey, null);
}

function getUserId() {
  var id = localStorage.getItem(userIdKey);
  if (id === null) {
    id = "user-" + Date.now() + "-" + Math.floor(Math.random() * 100000);
    localStorage.setItem(userIdKey, id);
  }
  return id;
}

function getMovie(movieId) {
  for (var i = 0; i < movies.length; i++) {
    if (movies[i].id === movieId) return movies[i];
  }
  return null;
}

function findShow(movie, showId) {
  if (movie === null) return null;
  for (var i = 0; i < movie.shows.length; i++) {
    if (movie.shows[i].id === showId) return movie.shows[i];
  }
  return null;
}

function money(amount) {
  return "₹" + amount;
}

function formatDate(date) {
  return new Date(date + "T12:00:00").toLocaleDateString("en-IN", {
    day: "numeric",
    month: "long",
    year: "numeric"
  });
}

function escapeHtml(value) {
  var text = String(value);
  text = text.replace(/&/g, "&amp;");
  text = text.replace(/</g, "&lt;");
  text = text.replace(/>/g, "&gt;");
  text = text.replace(/"/g, "&quot;");
  text = text.replace(/'/g, "&#39;");
  return text;
}

function makeFormBody(data) {
  var parts = [];
  for (var key in data) {
    if (Object.prototype.hasOwnProperty.call(data, key)) {
      parts.push(encodeURIComponent(key) + "=" + encodeURIComponent(data[key]));
    }
  }
  return parts.join("&");
}

async function api(path, data) {
  var options = {};
  if (data !== undefined) {
    options.method = "POST";
    options.headers = { "Content-Type": "application/x-www-form-urlencoded" };
    options.body = makeFormBody(data);
  }

  var response = await fetch("/api" + path, options);
  var result = await response.json();
  if (!response.ok) throw new Error(result.error);
  return result;
}

function poster(movie, extraClass) {
  var image = "";
  if (movie.posterUrl && movie.posterUrl.indexOf("https://") === 0) {
    image = `<img class="poster-image" src="${escapeHtml(movie.posterUrl)}" alt="Poster for ${escapeHtml(movie.title)}" onerror="this.remove()">`;
  }

  var imageClass = image ? " has-image" : "";
  return `<div class="poster ${escapeHtml(movie.accent)}${imageClass} ${extraClass}">${image}<span class="rating">${escapeHtml(movie.ratingSource)} ★ ${escapeHtml(movie.rating)}</span><span class="poster-title">${escapeHtml(movie.title)}</span></div>`;
}

function movieCard(movie) {
  var detailsUrl = "movie-details.html?id=" + encodeURIComponent(movie.id);
  var reviewLink = "";
  if (movie.reviewsUrl) {
    reviewLink = `<a class="small-link imdb-reviews" href="${escapeHtml(movie.reviewsUrl)}" target="_blank" rel="noopener noreferrer">IMDb reviews ↗</a>`;
  }

  return `<article class="movie-card"><a class="movie-card-link" href="${detailsUrl}">${poster(movie, "")}<div class="movie-info"><h3>${escapeHtml(movie.title)}</h3><p>${escapeHtml(movie.genre)} · ${escapeHtml(movie.duration)}</p><div class="movie-meta"><span class="small-link">${escapeHtml(movie.language)}</span><span class="small-link">View details ↗</span></div></div></a>${reviewLink ? `<div class="movie-extra">${reviewLink}</div>` : ""}</article>`;
}

function setError(message, action, actionLabel) {
  if (action === undefined) action = "movies.html";
  if (actionLabel === undefined) actionLabel = "Browse movies";
  app.innerHTML = `<div class="error-message"><h2>${escapeHtml(message)}</h2><p>Start by choosing a movie from the list.</p><a class="button" href="${action}">${actionLabel}</a></div>`;
}

function renderHome() {
  var movieCards = "";
  for (var i = 0; i < movies.length; i++) {
    movieCards += movieCard(movies[i]);
  }

  app.innerHTML = `<section class="hero"><div class="hero-copy"><p class="eyebrow">Your seat is waiting</p><h1>Big stories.<br><span>Better nights.</span></h1><p class="lead">Find your next film, pick your seats, and make an evening of it.</p><a class="button" href="movies.html">Explore movies <span aria-hidden="true">↗</span></a></div></section><div class="section-heading"><div><p class="eyebrow">On the big screen</p><h2>Now showing</h2></div><a class="small-link" href="movies.html">All movies ↗</a></div><section class="movie-grid">${movieCards}</section>`;
}

function renderMovies() {
  var movieCards = "";
  for (var i = 0; i < movies.length; i++) {
    movieCards += movieCard(movies[i]);
  }

  app.innerHTML = `<p class="eyebrow">Find your film</p><h1 class="page-title">Now showing</h1><p class="lead">Pick a movie to see showtimes and available seats.</p><label class="search-wrap"><input id="movie-search" type="search" placeholder="Search by title, genre, or language" aria-label="Search movies"></label><section class="movie-grid" id="movie-grid">${movieCards}</section>`;
  document.querySelector("#movie-search").addEventListener("input", function (event) {
    var query = event.target.value.trim().toLowerCase();
    var results = "";
    for (var i = 0; i < movies.length; i++) {
      var movie = movies[i];
      var text = (movie.title + " " + movie.genre + " " + movie.language).toLowerCase();
      if (text.indexOf(query) !== -1) results += movieCard(movie);
    }
    if (results === "") results = `<p class="empty-state">No movies match that search.</p>`;
    document.querySelector("#movie-grid").innerHTML = results;
  });
}

function renderDetails() {
  var movieId = new URLSearchParams(location.search).get("id");
  var movie = getMovie(movieId);
  if (movie === null) {
    setError("Movie not found");
    return;
  }
  document.title = movie.title + " | Reelhouse";

  var reviews = `<p class="small-link">IMDb reviews unavailable for this title</p>`;
  if (movie.reviewsUrl) {
    reviews = `<p><a class="small-link" href="${escapeHtml(movie.reviewsUrl)}" target="_blank" rel="noopener noreferrer">Read IMDb user reviews ↗</a></p>`;
  }

  var showRows = "";
  for (var i = 0; i < movie.shows.length; i++) {
    var show = movie.shows[i];
    showRows += `<div class="show-row"><div><strong>${escapeHtml(show.time)}</strong><p>Regular · ${money(show.price)} per seat</p></div><button class="show-button" data-show="${escapeHtml(show.id)}">Select show</button></div>`;
  }

  var cast = movie.actors || "Cast details are not included in the sample movie data. Set an OMDb API key to look them up.";
  var director = movie.director || "Not available in the sample movie data";
  var writer = movie.writer || "Not available in the sample movie data";
  app.innerHTML = `<p class="eyebrow">Now showing · ${escapeHtml(movie.genre)}</p><section class="movie-profile"><div>${poster(movie, "detail-poster")}</div><div class="movie-profile-info"><h1 class="page-title">${escapeHtml(movie.title)}</h1><p class="movie-facts">${escapeHtml(movie.duration)} <span>·</span> ${escapeHtml(movie.language)} <span>·</span> ${escapeHtml(movie.ratingSource)} ★ ${escapeHtml(movie.rating)}</p><h2>About the movie</h2><p class="lead movie-synopsis">${escapeHtml(movie.synopsis)}</p>${reviews}</div></section><section class="movie-section"><div class="section-heading"><div><p class="eyebrow">On the big screen</p><h2>${escapeHtml(movie.theatre)}</h2><p>${formatDate(movie.date)}</p></div></div><div class="show-list">${showRows}</div></section><section class="movie-section credits-section"><p class="eyebrow">People behind the movie</p><h2>Cast & crew</h2><div class="credits-grid"><div><h3>Cast</h3><p>${escapeHtml(cast)}</p></div><div><h3>Director</h3><p>${escapeHtml(director)}</p></div><div><h3>Writers</h3><p>${escapeHtml(writer)}</p></div></div></section>`;

  var showButtons = app.querySelectorAll("[data-show]");
  for (var i = 0; i < showButtons.length; i++) {
    showButtons[i].addEventListener("click", function (event) {
      saveStorage(selectionKey, {
        movieId: movie.id,
        showId: event.currentTarget.dataset.show,
        seats: []
      });
      location.href = "seats.html";
    });
  }
}

async function renderSeats(message) {
  var current = getSelection();
  var movie = current ? getMovie(current.movieId) : null;
  var show = movie ? findShow(movie, current.showId) : null;
  if (movie === null || show === null) {
    setError("Choose a show first");
    return;
  }

  var availability;
  try {
    var query = "?showId=" + encodeURIComponent(show.id) + "&userId=" + encodeURIComponent(getUserId());
    availability = await api("/seats" + query);
  } catch (error) {
    setError(error.message, "movie-details.html?id=" + encodeURIComponent(movie.id), "Choose another show");
    return;
  }

  current.seats = availability.ownSeats;
  saveStorage(selectionKey, current);

  var seatButtons = "";
  for (var row = 0; row < 4; row++) {
    for (var number = 1; number <= 8; number++) {
      var seat = String.fromCharCode(65 + row) + number;
      var selected = current.seats.indexOf(seat) !== -1;
      var blocked = availability.blocked.indexOf(seat) !== -1;
      var selectedClass = selected ? "selected" : "";
      var disabled = blocked ? "disabled" : "";
      seatButtons += `<button class="seat ${selectedClass}" data-seat="${seat}" aria-label="Seat ${seat}" aria-pressed="${selected}" ${disabled}>${seat}</button>`;
    }
  }

  var messageHtml = "";
  if (message) messageHtml = `<p class="hold-message" role="status">${escapeHtml(message)}</p>`;
  var continueDisabled = current.seats.length === 0 ? "disabled" : "";
  app.innerHTML = `<p class="eyebrow">${escapeHtml(movie.theatre)} · ${formatDate(movie.date)} · ${escapeHtml(show.time)}</p><h1 class="page-title">Pick your seats</h1><p class="lead">${escapeHtml(movie.title)} · ${money(show.price)} per seat</p>${messageHtml}<section class="seat-layout"><div class="screen">SCREEN</div><div class="seat-grid">${seatButtons}</div><div class="seat-legend"><span><i class="legend-dot"></i>Available</span><span><i class="legend-dot chosen"></i>Selected</span><span><i class="legend-dot unavailable"></i>Held or booked</span></div><p class="hold-note">Selected seats are held for five minutes and released automatically if you do not confirm.</p></section><div class="summary-row"><span>${current.seats.length} seats selected</span><strong>${money(current.seats.length * show.price)}</strong></div><button class="button" id="continue" ${continueDisabled}>Continue to summary</button>`;

  var buttons = app.querySelectorAll("[data-seat]");
  for (var i = 0; i < buttons.length; i++) {
    buttons[i].addEventListener("click", function (event) {
      changeSeat(show, event.currentTarget.dataset.seat);
    });
  }

  app.querySelector("#continue").addEventListener("click", function () {
    if (current.seats.length > 0) location.href = "booking.html";
  });
}

async function changeSeat(show, seat) {
  var current = getSelection();
  var newSeats = [];
  for (var i = 0; i < current.seats.length; i++) {
    if (current.seats[i] !== seat) newSeats.push(current.seats[i]);
  }
  if (newSeats.length === current.seats.length) newSeats.push(seat);

  var buttons = app.querySelectorAll("[data-seat]");
  for (var i = 0; i < buttons.length; i++) buttons[i].disabled = true;

  try {
    await api("/holds", {
      showId: show.id,
      userId: getUserId(),
      seats: newSeats.join(",")
    });
    current.seats = newSeats;
    saveStorage(selectionKey, current);
    await renderSeats("");
  } catch (error) {
    await renderSeats(error.message);
  }
}

async function renderBooking() {
  var current = getSelection();
  var movie = current ? getMovie(current.movieId) : null;
  var show = movie ? findShow(movie, current.showId) : null;
  if (movie === null || show === null || current.seats.length === 0) {
    setError("Your booking is not ready");
    return;
  }

  var hold;
  try {
    var query = "?showId=" + encodeURIComponent(show.id) + "&userId=" + encodeURIComponent(getUserId());
    hold = await api("/seats" + query);
  } catch (error) {
    setError(error.message, "seats.html", "Return to seats");
    return;
  }

  var missingSeat = false;
  for (var i = 0; i < current.seats.length; i++) {
    if (hold.ownSeats.indexOf(current.seats[i]) === -1) missingSeat = true;
  }
  if (hold.expiresAt === 0 || missingSeat) {
    setError("Your seat hold expired. Please select your seats again.", "seats.html", "Choose seats again");
    return;
  }

  var subtotal = show.price * current.seats.length;
  var total = subtotal + 30;
  var seatNames = current.seats.join(", ");
  app.innerHTML = `<p class="eyebrow">One last look</p><h1 class="page-title">Booking summary</h1><section class="summary"><h2>${escapeHtml(movie.title)}</h2><p class="lead">${escapeHtml(movie.theatre)} · ${formatDate(movie.date)} · ${escapeHtml(show.time)}</p><div class="summary-row"><span>Seats</span><strong>${escapeHtml(seatNames)}</strong></div><div class="summary-row"><span>${current.seats.length} × ${money(show.price)}</span><strong>${money(subtotal)}</strong></div><div class="summary-row"><span>Convenience fee</span><strong>${money(30)}</strong></div><div class="summary-row total"><span>Total</span><strong>${money(total)}</strong></div><p class="hold-message" id="hold-countdown" role="timer"></p><div class="booking-actions"><a class="button secondary" href="seats.html">&larr; Back to seats</a><button class="button" id="confirm">Confirm demo payment</button></div><p class="lead">Demo only: no payment gateway is connected and no payment will be collected.</p></section>`;

  var countdown = app.querySelector("#hold-countdown");
  function updateCountdown() {
    var seconds = Math.ceil((hold.expiresAt - Date.now()) / 1000);
    if (seconds < 0) seconds = 0;
    var minutes = Math.floor(seconds / 60);
    var remainingSeconds = String(seconds % 60).padStart(2, "0");
    countdown.textContent = "Seats held for " + minutes + ":" + remainingSeconds;
    if (seconds === 0) app.querySelector("#confirm").disabled = true;
  }

  updateCountdown();
  var timer = setInterval(updateCountdown, 1000);
  app.querySelector("#confirm").addEventListener("click", async function (event) {
    event.currentTarget.disabled = true;
    try {
      var booking = await api("/bookings", {
        showId: show.id,
        userId: getUserId(),
        seats: current.seats.join(",")
      });
      clearInterval(timer);
      location.href = "ticket.html?id=" + encodeURIComponent(booking.id);
    } catch (error) {
      clearInterval(timer);
      setError(error.message, "seats.html", "Choose seats again");
    }
  });
}

function ticketMarkup(booking) {
  var seats = booking.seats.join(", ");
  return `<article class="ticket"><div class="ticket-top"><p class="eyebrow">Reelhouse · admission ticket</p><h2>${escapeHtml(booking.movieTitle)}</h2><p class="lead">${escapeHtml(booking.theatre)}</p></div><div class="ticket-body"><div class="summary-row"><span>Date & time</span><strong>${formatDate(booking.date)} · ${escapeHtml(booking.time)}</strong></div><div class="summary-row"><span>Seats</span><strong>${escapeHtml(seats)}</strong></div><div class="summary-row"><span>Booking ID</span><strong>${escapeHtml(booking.id)}</strong></div><div class="summary-row total"><span>Confirmed total</span><strong>${money(booking.total)}</strong></div><div class="ticket-code">${escapeHtml(booking.id)}</div></div></article>`;
}

async function renderTicket() {
  var bookingId = new URLSearchParams(location.search).get("id");
  if (bookingId === null) {
    setError("No ticket to display");
    return;
  }

  var query = "?userId=" + encodeURIComponent(getUserId()) + "&id=" + encodeURIComponent(bookingId);
  var bookings = await api("/bookings" + query);
  if (bookings.length === 0) {
    setError("Ticket not found");
    return;
  }

  app.innerHTML = `<p class="eyebrow">You're all set</p><h1 class="page-title">Your ticket</h1>${ticketMarkup(bookings[0])}<p style="text-align:center;margin-top:22px"><a class="button secondary" href="bookings.html">View my bookings</a></p>`;
}

async function renderBookings() {
  var query = "?userId=" + encodeURIComponent(getUserId());
  var bookings = await api("/bookings" + query);
  var items = "";

  for (var i = bookings.length - 1; i >= 0; i--) {
    var booking = bookings[i];
    var seats = booking.seats.join(", ");
    items += `<article class="booking-item"><h3>${escapeHtml(booking.movieTitle)}</h3><p class="lead">${escapeHtml(booking.theatre)} · ${formatDate(booking.date)} · ${escapeHtml(booking.time)}</p><div class="movie-meta"><span class="small-link">Seats ${escapeHtml(seats)} · ${money(booking.total)}</span><a class="small-link" href="ticket.html?id=${encodeURIComponent(booking.id)}">View ticket ↗</a></div></article>`;
  }

  if (items === "") items = `<div class="empty-state">No bookings yet. Choose a movie to plan your first visit.</div>`;
  app.innerHTML = `<p class="eyebrow">Your movie nights</p><h1 class="page-title">My bookings</h1>${items}`;
}

async function startPage() {
  try {
    movies = await api("/movies");
    if (page === "home") renderHome();
    else if (page === "movies") renderMovies();
    else if (page === "details") renderDetails();
    else if (page === "seats") await renderSeats("");
    else if (page === "booking") await renderBooking();
    else if (page === "ticket") await renderTicket();
    else if (page === "bookings") await renderBookings();
    else renderHome();
  } catch (error) {
    setError("Could not connect to the Java server. " + error.message, "README.md", "Server setup");
  }
}

startPage();

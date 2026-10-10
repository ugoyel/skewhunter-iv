package com.carsathi.app.data.seed

import androidx.room.withTransaction
import com.carsathi.app.data.SessionStore
import com.carsathi.app.data.catalog.CarCatalog
import com.carsathi.app.data.catalog.City
import com.carsathi.app.data.catalog.CityCatalog
import com.carsathi.app.data.catalog.Pricing
import com.carsathi.app.data.db.AppDatabase
import com.carsathi.app.data.db.BookingEntity
import com.carsathi.app.data.db.CarEntity
import com.carsathi.app.data.db.ConversationEntity
import com.carsathi.app.data.db.MessageEntity
import com.carsathi.app.data.db.NotificationEntity
import com.carsathi.app.data.db.RatingEntity
import com.carsathi.app.data.db.RideEntity
import com.carsathi.app.data.db.UserEntity
import com.carsathi.app.data.model.matchSegment
import com.carsathi.app.data.model.points
import com.carsathi.app.data.repo.NotificationRepository
import com.carsathi.app.data.repo.Passwords
import com.carsathi.app.data.repo.RideDraft
import com.carsathi.app.data.repo.RideRepository
import com.carsathi.app.data.repo.WalletRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Seeds the local database with a community of demo members, their cars & ratings, and a rolling
 * three-week schedule of rides on popular Indian routes, so search works out of the box.
 */
class DemoSeeder(
    private val db: AppDatabase,
    private val session: SessionStore,
    private val rides: RideRepository,
    private val notifications: NotificationRepository,
    private val wallet: WalletRepository,
) {
    private data class Member(val first: String, val last: String, val gender: String, val bio: String)

    private data class SeedRoute(val from: String, val to: String, val via: List<String> = emptyList(), val weight: Int = 1)

    private val members = listOf(
        Member("Aarav", "Mehta", "M", "Product designer, weekend trekker. Always up for good music on the road."),
        Member("Priya", "Sharma", "F", "Software engineer travelling home to Jaipur most weekends."),
        Member("Rohan", "Gupta", "M", "Consultant. I drive carefully and like to leave on time."),
        Member("Ananya", "Iyer", "F", "Doctor in training, love road trips and filter coffee stops."),
        Member("Vikram", "Singh", "M", "Ex-army, punctual and calm driver. Chai breaks are mandatory."),
        Member("Sneha", "Patil", "F", "Architect from Pune. Happy to share my car to Mumbai and back."),
        Member("Arjun", "Nair", "M", "Startup founder. I commute Bengaluru–Mysuru twice a week."),
        Member("Neha", "Kapoor", "F", "Teacher. Quiet rides, good playlists."),
        Member("Karan", "Malhotra", "M", "Sales manager. Driving across North India for work."),
        Member("Kavya", "Menon", "F", "Photographer — I love hill stations and stopping for views."),
        Member("Siddharth", "Rao", "M", "Data analyst. Calm driver, AC always on."),
        Member("Pooja", "Desai", "F", "Chartered accountant, Ahmedabad ↔ Vadodara regular."),
        Member("Aditya", "Kulkarni", "M", "Cyclist and runner, happy to carry sports gear."),
        Member("Riya", "Banerjee", "F", "Journalist from Kolkata. Love long conversations."),
        Member("Rahul", "Verma", "M", "Engineer. Delhi ↔ Chandigarh almost every Friday."),
        Member("Meera", "Pillai", "F", "Kochi based. Weekend trips to Munnar and Alleppey."),
        Member("Imran", "Khan", "M", "Hotel manager, friendly and always on time."),
        Member("Simran", "Kaur", "F", "Amritsar girl living in Delhi. Punjabi music guaranteed!"),
        Member("Harpreet", "Gill", "M", "Business owner. Comfortable SUV, lots of luggage space."),
        Member("Aisha", "Siddiqui", "F", "Lawyer in Lucknow. I prefer women co-travellers."),
        Member("Manish", "Agarwal", "M", "Trader travelling Jaipur–Delhi weekly."),
        Member("Divya", "Krishnan", "F", "UX researcher in Chennai, Pondicherry weekends."),
        Member("Suresh", "Iyer", "M", "Retired banker, safe and steady driver."),
        Member("Tanvi", "Shah", "F", "MBA student, budget traveller."),
        Member("Nikhil", "Joshi", "M", "Mechanical engineer. My car is always serviced on time."),
        Member("Lakshmi", "Rao", "F", "Hyderabad ↔ Vijayawada for family visits."),
        Member("Pranav", "Reddy", "M", "Hyderabadi biryani lover, happy to recommend stops."),
        Member("Fatima", "Sheikh", "F", "Nurse working shifts, often travelling early mornings."),
        Member("Amit", "Patel", "M", "Diamond trader from Surat, Mumbai most weeks."),
        Member("Deepak", "Yadav", "M", "Government officer. Smoke-free car, pets welcome."),
        Member("Joseph", "Thomas", "M", "Chef from Kerala. Snacks for everyone on the way!"),
        Member("Sandeep", "Chauhan", "M", "Hotelier in Dehradun, Delhi trips every week."),
    )

    private val routes = listOf(
        SeedRoute("Delhi", "Jaipur", listOf("Gurugram"), 4),
        SeedRoute("Delhi", "Agra", listOf("Noida", "Mathura"), 3),
        SeedRoute("Delhi", "Chandigarh", listOf("Panipat", "Ambala"), 4),
        SeedRoute("Delhi", "Dehradun", listOf("Meerut", "Roorkee"), 3),
        SeedRoute("Delhi", "Rishikesh", listOf("Meerut", "Haridwar"), 2),
        SeedRoute("Delhi", "Lucknow", listOf("Agra"), 2),
        SeedRoute("Delhi", "Amritsar", listOf("Panipat", "Ludhiana", "Jalandhar"), 2),
        SeedRoute("Delhi", "Manali", listOf("Chandigarh", "Kullu"), 1),
        SeedRoute("Delhi", "Shimla", listOf("Chandigarh", "Solan"), 2),
        SeedRoute("Delhi", "Nainital", listOf("Moradabad", "Haldwani"), 1),
        SeedRoute("Gurugram", "Jaipur", weight = 2),
        SeedRoute("Noida", "Agra", weight = 1),
        SeedRoute("Delhi", "Meerut", weight = 1),
        SeedRoute("Chandigarh", "Manali", listOf("Kullu"), 1),
        SeedRoute("Chandigarh", "Shimla", listOf("Solan"), 2),
        SeedRoute("Ludhiana", "Chandigarh", weight = 1),
        SeedRoute("Jammu", "Katra", weight = 1),
        SeedRoute("Jammu", "Srinagar", weight = 1),
        SeedRoute("Mumbai", "Pune", listOf("Lonavala"), 5),
        SeedRoute("Mumbai", "Nashik", listOf("Thane"), 2),
        SeedRoute("Mumbai", "Surat", listOf("Vapi"), 2),
        SeedRoute("Mumbai", "Panaji", listOf("Ratnagiri"), 1),
        SeedRoute("Mumbai", "Ahmedabad", listOf("Surat", "Vadodara"), 2),
        SeedRoute("Pune", "Panaji", listOf("Kolhapur"), 1),
        SeedRoute("Pune", "Nashik", weight = 1),
        SeedRoute("Pune", "Mahabaleshwar", weight = 1),
        SeedRoute("Pune", "Aurangabad", listOf("Ahmednagar"), 1),
        SeedRoute("Ahmedabad", "Vadodara", weight = 2),
        SeedRoute("Ahmedabad", "Udaipur", weight = 1),
        SeedRoute("Ahmedabad", "Rajkot", weight = 1),
        SeedRoute("Jaipur", "Udaipur", listOf("Ajmer"), 1),
        SeedRoute("Jaipur", "Jodhpur", listOf("Ajmer"), 1),
        SeedRoute("Bengaluru", "Mysuru", weight = 5),
        SeedRoute("Bengaluru", "Chennai", listOf("Hosur", "Vellore"), 4),
        SeedRoute("Bengaluru", "Hyderabad", listOf("Anantapur", "Kurnool"), 2),
        SeedRoute("Bengaluru", "Coimbatore", listOf("Hosur", "Salem"), 2),
        SeedRoute("Bengaluru", "Mangaluru", weight = 1),
        SeedRoute("Bengaluru", "Puducherry", weight = 1),
        SeedRoute("Bengaluru", "Panaji", listOf("Hubballi"), 1),
        SeedRoute("Bengaluru", "Madikeri (Coorg)", listOf("Mysuru"), 1),
        SeedRoute("Chennai", "Puducherry", listOf("Mahabalipuram"), 3),
        SeedRoute("Chennai", "Tirupati", weight = 2),
        SeedRoute("Chennai", "Madurai", listOf("Tiruchirappalli"), 1),
        SeedRoute("Hyderabad", "Vijayawada", weight = 2),
        SeedRoute("Hyderabad", "Warangal", weight = 1),
        SeedRoute("Vijayawada", "Visakhapatnam", listOf("Rajahmundry"), 1),
        SeedRoute("Kochi", "Thiruvananthapuram", listOf("Alappuzha"), 2),
        SeedRoute("Kochi", "Munnar", weight = 1),
        SeedRoute("Kochi", "Kozhikode", listOf("Thrissur"), 1),
        SeedRoute("Coimbatore", "Ooty", weight = 1),
        SeedRoute("Kolkata", "Durgapur", listOf("Howrah"), 2),
        SeedRoute("Kolkata", "Digha", weight = 1),
        SeedRoute("Siliguri", "Darjeeling", weight = 1),
        SeedRoute("Bhubaneswar", "Puri", weight = 1),
        SeedRoute("Lucknow", "Kanpur", weight = 2),
        SeedRoute("Lucknow", "Varanasi", listOf("Prayagraj"), 1),
        SeedRoute("Indore", "Bhopal", weight = 2),
        SeedRoute("Indore", "Ujjain", weight = 1),
        SeedRoute("Patna", "Gaya", weight = 1),
        SeedRoute("Ranchi", "Jamshedpur", weight = 1),
        SeedRoute("Guwahati", "Shillong", weight = 1),
        SeedRoute("Nagpur", "Raipur", weight = 1),
    )

    private val rideComments = listOf(
        "Pickup flexible within the city. Please be on time 🙏",
        "One medium bag per person please. AC car, smooth ride.",
        "I usually stop once for chai on the way.",
        "Leaving straight after office, small delays possible.",
        "Happy to drop you near a metro station.",
        "No smoking please. Water bottles available in the car.",
        "Comfortable highway drive, toll included in the price.",
        "",
        "",
        "",
    )

    private val driverReviews = listOf(
        "Great driver, very punctual and safe!", "Smooth ride and nice conversation.", "Car was spotless, would ride again.",
        "Very friendly, dropped me right at the station.", "Safe driving even in heavy traffic.", "Good music and a comfortable car.",
        "Left exactly on time. Recommended!", "Helpful with luggage, very polite.", "Excellent trip, thank you!",
        "A bit late at pickup but otherwise fine.", "Pleasant journey, stopped for a quick chai.",
    )

    private val passengerReviews = listOf(
        "Lovely co-traveller, on time.", "Very polite passenger.", "Great company on a long drive!",
        "Punctual and friendly.", "Easy-going and respectful.", "Would happily ride together again.",
    )

    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        if (db.users().demoUsers().isEmpty()) seedMembers()
        topUpRides()
        if (db.users().byEmail(DEMO_EMAIL) == null) seedDemoAccount()
    }

    // ------------------------------------------------------------------------------- members

    private suspend fun seedMembers() {
        val rnd = Random(42)
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val users = members.mapIndexed { i, m ->
            UserEntity(
                firstName = m.first,
                lastName = m.last,
                email = "${m.first.lowercase()}.${m.last.lowercase()}@members.carsathi.in",
                phone = "+91 9${rnd.nextInt(100_000_000, 999_999_999)}",
                birthDate = LocalDate.now().minusYears(rnd.nextLong(22, 58)).minusDays(rnd.nextLong(0, 365)).toEpochDay(),
                gender = m.gender,
                bio = m.bio,
                memberSince = now - rnd.nextLong(120, 2_200) * day,
                emailVerified = true,
                phoneVerified = rnd.nextInt(10) < 9,
                idStatus = if (rnd.nextInt(10) < 7) "VERIFIED" else "NONE",
                idDocType = "Aadhaar",
                chattiness = rnd.nextInt(3),
                music = rnd.nextInt(3),
                smoking = if (rnd.nextInt(10) < 8) 0 else rnd.nextInt(1, 3),
                pets = rnd.nextInt(3),
                isDemo = true,
                pastRides = if (i % 7 == 6) rnd.nextInt(0, 3) else rnd.nextInt(5, 140),
            )
        }
        db.withTransaction {
            val ids = db.users().insertAll(users)
            val brands = CarCatalog.brandNames.take(12)
            val cars = ids.mapIndexed { i, id ->
                val brand = brands[rnd.nextInt(brands.size)]
                val model = CarCatalog.models(brand).let { it[rnd.nextInt(it.size)] }
                CarEntity(
                    ownerId = id, brand = brand, model = model.name, bodyType = model.bodyType,
                    color = CarCatalog.colors[rnd.nextInt(8)].name, year = rnd.nextInt(2014, 2026),
                    plate = randomPlate(rnd), fuel = listOf("Petrol", "Diesel", "CNG", "Petrol", "Electric")[rnd.nextInt(5)],
                    createdAt = now - i,
                )
            }
            db.cars().insertAll(cars)
            val ratings = mutableListOf<RatingEntity>()
            ids.forEachIndexed { i, id ->
                val count = (users[i].pastRides / 4).coerceIn(0, 24)
                repeat(count) {
                    var author = ids[rnd.nextInt(ids.size)]
                    if (author == id) author = ids[(ids.indexOf(id) + 1) % ids.size]
                    val asDriver = rnd.nextInt(10) < 8
                    ratings += RatingEntity(
                        rideId = 0, fromUserId = author, toUserId = id, stars = weightedStars(rnd),
                        comment = if (asDriver) driverReviews[rnd.nextInt(driverReviews.size)] else passengerReviews[rnd.nextInt(passengerReviews.size)],
                        toRole = if (asDriver) "DRIVER" else "PASSENGER",
                        createdAt = now - rnd.nextLong(2, 400) * day,
                    )
                }
            }
            db.ratings().insertAll(ratings)
        }
    }

    private fun weightedStars(rnd: Random): Int {
        val r = rnd.nextInt(100)
        return when {
            r < 66 -> 5
            r < 91 -> 4
            r < 97 -> 3
            r < 99 -> 2
            else -> 1
        }
    }

    private fun randomPlate(rnd: Random): String {
        val states = listOf("DL", "HR", "UP", "MH", "KA", "TN", "TS", "GJ", "RJ", "KL", "PB", "WB")
        val letters = ('A'..'Z').toList()
        return "${states[rnd.nextInt(states.size)]} ${rnd.nextInt(1, 15).toString().padStart(2, '0')} " +
            "${letters[rnd.nextInt(26)]}${letters[rnd.nextInt(26)]} ${rnd.nextInt(1000, 9999)}"
    }

    // ------------------------------------------------------------------------------- rides

    /** Keeps a rolling window of demo rides from today to three weeks ahead. */
    private suspend fun topUpRides() {
        val today = LocalDate.now().toEpochDay()
        val horizon = today + 21
        val seededUntil = session.seededUntil()
        val start = maxOf(today, seededUntil + 1)
        if (start > horizon) return
        val drivers = db.users().demoUsers()
        if (drivers.isEmpty()) return
        val carsByOwner = drivers.associate { d -> d.id to db.cars().byOwner(d.id).firstOrNull() }
        val newRides = mutableListOf<RideEntity>()
        for (day in start..horizon) newRides += generateDay(LocalDate.ofEpochDay(day), drivers, carsByOwner)
        db.withTransaction {
            val ids = db.rides().insertAll(newRides)
            val bookings = mutableListOf<BookingEntity>()
            ids.forEachIndexed { i, id ->
                val ride = newRides[i]
                val rnd = Random(id)
                val n = rnd.nextInt(0, (ride.seatsTotal).coerceAtMost(3))
                val candidates = drivers.filter { it.id != ride.driverId && (!ride.womenOnly || it.gender == "F") }.shuffled(rnd).take(n)
                candidates.forEach { p ->
                    bookings += BookingEntity(
                        rideId = id, passengerId = p.id, seats = 1, fromCity = ride.fromCity, toCity = ride.toCity,
                        pricePerSeat = ride.pricePerSeat, serviceFee = Pricing.serviceFee(ride.pricePerSeat),
                        total = ride.pricePerSeat + Pricing.serviceFee(ride.pricePerSeat), status = "CONFIRMED", paymentMethod = "CASH",
                        createdAt = ride.createdAt,
                    )
                }
            }
            db.bookings().insertAll(bookings)
        }
        session.setSeededUntil(horizon)
        db.rides().pruneOldDemoRides(System.currentTimeMillis() - 10 * 86_400_000L)
        if (seededUntil > 0) notifyAlerts(newRides)
    }

    private fun generateDay(date: LocalDate, drivers: List<UserEntity>, cars: Map<Long, CarEntity?>): List<RideEntity> {
        val rnd = Random(date.toEpochDay() * 31 + 7)
        val zone = ZoneId.systemDefault()
        val out = mutableListOf<RideEntity>()
        val weekendBoost = if (date.dayOfWeek.value >= 5) 1 else 0
        for (route in routes) {
            for (reverse in listOf(false, true)) {
                val count = (route.weight + weekendBoost + rnd.nextInt(-1, 2)).coerceAtLeast(0)
                repeat(count) {
                    val fromName = if (reverse) route.to else route.from
                    val toName = if (reverse) route.from else route.to
                    val from = CityCatalog.find(fromName) ?: return@repeat
                    val to = CityCatalog.find(toName) ?: return@repeat
                    val via = (if (reverse) route.via.reversed() else route.via).mapNotNull { CityCatalog.find(it) }
                    val driver = drivers[rnd.nextInt(drivers.size)]
                    val car = cars[driver.id]
                    val hour = listOf(5, 6, 6, 7, 7, 8, 9, 10, 11, 13, 14, 15, 16, 17, 18, 18, 19, 20, 21)[rnd.nextInt(19)]
                    val minute = listOf(0, 15, 30, 45)[rnd.nextInt(4)]
                    val departure = date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
                    val km = rides.routeKm(from, via, to)
                    val rec = Pricing.recommended(km)
                    val price = ((rec * (0.8 + rnd.nextDouble() * 0.35)) / 10).roundToInt() * 10
                    val maxSeats = car?.let { CarCatalog.seatsFor(it.bodyType) } ?: 3
                    val fromPt = CityCatalog.meetingPoints(from).let { it[rnd.nextInt(it.size)] }
                    val toPt = CityCatalog.meetingPoints(to).let { it[rnd.nextInt(it.size)] }
                    val draft = RideDraft(
                        from = from.copy(lat = fromPt.lat, lng = fromPt.lng),
                        fromAddress = fromPt.label,
                        to = to.copy(lat = toPt.lat, lng = toPt.lng),
                        toAddress = toPt.label,
                        stopovers = via,
                        departureAt = departure,
                        seats = rnd.nextInt(2, maxSeats.coerceAtLeast(2) + 1),
                        maxTwoInBack = rnd.nextInt(10) < 4,
                        instantBooking = rnd.nextInt(10) < 6,
                        womenOnly = driver.gender == "F" && rnd.nextInt(10) < 2,
                        pricePerSeat = price.coerceAtLeast(50),
                        carId = car?.id,
                        comment = rideComments[rnd.nextInt(rideComments.size)],
                    )
                    out += rides.buildRide(driver.id, draft).copy(createdAt = departure - rnd.nextLong(1, 10) * 86_400_000L)
                }
            }
        }
        return out
    }

    private suspend fun notifyAlerts(newRides: List<RideEntity>) {
        val alerts = db.alerts().all()
        if (alerts.isEmpty()) return
        alerts.forEach { alert ->
            val from = CityCatalog.find(alert.fromCity) ?: return@forEach
            val to = CityCatalog.find(alert.toCity) ?: return@forEach
            val matches = newRides.count { r ->
                val dayOk = alert.dateEpochDay < 0 ||
                    LocalDate.ofEpochDay(alert.dateEpochDay) == java.time.Instant.ofEpochMilli(r.departureAt).atZone(ZoneId.systemDefault()).toLocalDate()
                dayOk && matchSegment(r.points(), from, to) != null
            }
            if (matches > 0) {
                notifications.notify(
                    alert.userId, "ALERT", "New rides: ${alert.fromCity} → ${alert.toCity}",
                    "$matches new ride${if (matches > 1) "s" else ""} match your ride alert.", alert.id,
                )
            }
        }
    }

    // ------------------------------------------------------------------------------- demo account

    /** A ready-made account with history so every feature can be explored right after install. */
    private suspend fun seedDemoAccount() {
        val members = db.users().demoUsers()
        if (members.size < 6) return
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val salt = Passwords.newSalt()
        val me = UserEntity(
            firstName = "Kabir", lastName = "Anand", email = DEMO_EMAIL, phone = "+91 98100 12345",
            passwordHash = Passwords.hash(DEMO_PASSWORD, salt), salt = salt,
            birthDate = LocalDate.now().minusYears(29).minusDays(40).toEpochDay(), gender = "M",
            bio = "Product manager in Delhi. I drive to Jaipur and Chandigarh often — happy to share the ride!",
            memberSince = now - 400 * day, emailVerified = true, phoneVerified = true, idStatus = "VERIFIED", idDocType = "Driving licence",
            chattiness = 2, music = 2, smoking = 0, pets = 1, payoutUpi = "kabir@okaxis",
        )
        val meId = db.users().insert(me)
        val carId = db.cars().insert(
            CarEntity(ownerId = meId, brand = "Maruti Suzuki", model = "Swift", bodyType = "Hatchback", color = "White", year = 2021, plate = "DL 3C AB 1234"),
        )
        fun city(n: String): City = CityCatalog.find(n)!!
        val zone = ZoneId.systemDefault()
        fun at(daysFromToday: Long, h: Int, m: Int) =
            LocalDate.now().plusDays(daysFromToday).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

        val p1 = members[1]; val p2 = members[7]; val d1 = members[6]; val d2 = members[14]; val p3 = members[12]; val p4 = members[17]

        // 1) Past ride as driver: Delhi → Jaipur, two passengers who rated Kabir
        val pastRide = rides.buildRide(
            meId,
            RideDraft(CityCatalog.point("Delhi", "Dhaula Kuan"), "Dhaula Kuan", CityCatalog.point("Jaipur", "Sindhi Camp Bus Stand"), "Sindhi Camp Bus Stand", listOf(city("Gurugram")), at(-10, 7, 30),
                3, true, true, false, 450, carId, "Leaving early to beat traffic."),
        )
        val pastRideId = db.rides().insert(pastRide)
        listOf(p1, p2).forEach { p ->
            db.bookings().insert(
                BookingEntity(rideId = pastRideId, passengerId = p.id, seats = 1, fromCity = "Delhi", toCity = "Jaipur", pricePerSeat = 450,
                    serviceFee = 36, total = 486, status = "CONFIRMED", paymentMethod = "UPI", createdAt = now - 14 * day),
            )
        }
        db.ratings().insert(RatingEntity(rideId = pastRideId, fromUserId = p1.id, toUserId = meId, stars = 5, comment = "Kabir is a fantastic driver, super punctual!", toRole = "DRIVER", createdAt = now - 9 * day))
        db.ratings().insert(RatingEntity(rideId = pastRideId, fromUserId = p2.id, toUserId = meId, stars = 4, comment = "Comfortable ride, good music.", toRole = "DRIVER", createdAt = now - 9 * day))

        // 2) Past ride as passenger: Bengaluru → Mysuru, already rated
        val pastTrip = rides.buildRide(
            d1.id,
            RideDraft(CityCatalog.point("Bengaluru", "Mysore Road Satellite Bus Stand"), "Mysore Road Satellite Bus Stand", CityCatalog.point("Mysuru", "Mysuru Palace"), "Mysuru Palace", emptyList(), at(-20, 9, 0),
                3, false, true, false, 280, db.cars().byOwner(d1.id).firstOrNull()?.id, ""),
        )
        val pastTripId = db.rides().insert(pastTrip)
        val pastBookingId = db.bookings().insert(
            BookingEntity(rideId = pastTripId, passengerId = meId, seats = 1, fromCity = "Bengaluru", toCity = "Mysuru", pricePerSeat = 280,
                serviceFee = 22, total = 302, status = "CONFIRMED", paymentMethod = "UPI", createdAt = now - 24 * day),
        )
        db.ratings().insert(RatingEntity(rideId = pastTripId, fromUserId = meId, toUserId = d1.id, stars = 5, comment = "Lovely ride, Arjun is a great host.", toRole = "DRIVER", createdAt = now - 19 * day))
        db.ratings().insert(RatingEntity(rideId = pastTripId, fromUserId = d1.id, toUserId = meId, stars = 5, comment = "Kabir was on time and great company.", toRole = "PASSENGER", createdAt = now - 19 * day))
        wallet.record(meId, "PAYMENT", 302, "Booking Bengaluru → Mysuru", "UPI", pastBookingId, now - 24 * day)

        // 3) Upcoming booking: Delhi → Chandigarh with a chat
        val upcomingTrip = rides.buildRide(
            d2.id,
            RideDraft(CityCatalog.point("Delhi", "Kashmere Gate ISBT"), "Kashmere Gate ISBT", CityCatalog.point("Chandigarh", "ISBT Sector 43"), "ISBT Sector 43", listOf(city("Panipat"), city("Ambala")), at(3, 17, 30),
                3, true, false, false, 420, db.cars().byOwner(d2.id).firstOrNull()?.id, "Friday evening drive, one stop for snacks."),
        )
        val upcomingTripId = db.rides().insert(upcomingTrip)
        val upBookingId = db.bookings().insert(
            BookingEntity(rideId = upcomingTripId, passengerId = meId, seats = 1, fromCity = "Delhi", toCity = "Chandigarh", pricePerSeat = 420,
                serviceFee = 34, total = 454, status = "CONFIRMED", paymentMethod = "UPI", createdAt = now - day),
        )
        wallet.record(meId, "PAYMENT", 454, "Booking Delhi → Chandigarh", "UPI", upBookingId, now - day)
        val convId = db.chat().insertConversation(ConversationEntity(rideId = upcomingTripId, passengerId = meId, driverId = d2.id))
        db.chat().insertMessage(MessageEntity(conversationId = convId, senderId = meId, text = "Hi Rahul! Can I carry a small backpack and a laptop bag?", sentAt = now - 20 * 3_600_000L))
        db.chat().insertMessage(MessageEntity(conversationId = convId, senderId = d2.id, text = "Hi Kabir, sure — plenty of space. See you at Kashmere Gate at 17:30!", sentAt = now - 19 * 3_600_000L))
        db.chat().updateConversation(
            ConversationEntity(id = convId, rideId = upcomingTripId, passengerId = meId, driverId = d2.id,
                lastMessage = "Hi Kabir, sure — plenty of space. See you at Kashmere Gate at 17:30!", lastAt = now - 19 * 3_600_000L, unreadPassenger = 1),
        )

        // 4) Upcoming ride as driver: Delhi → Agra, one confirmed passenger and one pending request
        val myRide = rides.buildRide(
            meId,
            RideDraft(CityCatalog.point("Delhi", "Akshardham Metro Station"), "Akshardham Metro Station", CityCatalog.point("Agra", "Taj Mahal East Gate"), "Taj Mahal East Gate", listOf(city("Mathura")), at(5, 8, 0),
                3, true, false, false, 380, carId, "Weekend trip to Agra. Medium bag only please."),
        )
        val myRideId = db.rides().insert(myRide)
        db.bookings().insert(
            BookingEntity(rideId = myRideId, passengerId = p3.id, seats = 1, fromCity = "Delhi", toCity = "Agra", pricePerSeat = 380,
                serviceFee = 30, total = 410, status = "CONFIRMED", paymentMethod = "CASH", createdAt = now - 2 * day),
        )
        db.bookings().insert(
            BookingEntity(rideId = myRideId, passengerId = p4.id, seats = 1, fromCity = "Delhi", toCity = "Mathura", pricePerSeat = 220,
                serviceFee = 18, total = 238, status = "PENDING", paymentMethod = "UPI", message = "Hi! Could you drop me at Mathura?", createdAt = now - 3_600_000L),
        )
        val conv2 = db.chat().insertConversation(ConversationEntity(rideId = myRideId, passengerId = p4.id, driverId = meId))
        db.chat().insertMessage(MessageEntity(conversationId = conv2, senderId = p4.id, text = "Hi! Could you drop me at Mathura?", sentAt = now - 3_600_000L))
        db.chat().updateConversation(
            ConversationEntity(id = conv2, rideId = myRideId, passengerId = p4.id, driverId = meId,
                lastMessage = "Hi! Could you drop me at Mathura?", lastAt = now - 3_600_000L, unreadDriver = 1),
        )

        wallet.record(meId, "PAYOUT", 900, "Payout · Delhi → Jaipur (2 passengers)", "Bank transfer", 0, now - 8 * day)
        wallet.addMethod(meId, "UPI", "UPI · kabir@okaxis", "kabir@okaxis")
        db.alerts().insert(com.carsathi.app.data.db.AlertEntity(userId = meId, fromCity = "Mumbai", toCity = "Pune", dateEpochDay = -1, seats = 1))

        listOf(
            NotificationEntity(userId = meId, type = "WELCOME", title = "Welcome to carsathi, Kabir!", body = "This is a demo account packed with rides, chats and ratings.", createdAt = now - 400 * day, read = true),
            NotificationEntity(userId = meId, type = "RATING", title = "New rating received", body = "${p1.displayName} rated you 5★ — Outstanding.", refId = meId, createdAt = now - 9 * day, read = true),
            NotificationEntity(userId = meId, type = "MESSAGE", title = "New message from ${d2.displayName}", body = "Hi Kabir, sure — plenty of space.", refId = convId, createdAt = now - 19 * 3_600_000L),
            NotificationEntity(userId = meId, type = "BOOKING_REQUEST", title = "New booking request", body = "${p4.displayName} wants to book 1 seat on Delhi → Agra.", refId = myRideId, createdAt = now - 3_600_000L),
        ).forEach { db.notifications().insert(it) }
    }

    companion object {
        const val DEMO_EMAIL = "demo@carsathi.in"
        const val DEMO_PASSWORD = "carsathi123"
    }
}

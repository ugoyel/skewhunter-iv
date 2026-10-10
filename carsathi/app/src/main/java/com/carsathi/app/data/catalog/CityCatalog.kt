package com.carsathi.app.data.catalog

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class City(val name: String, val state: String, val lat: Double, val lng: Double) {
    val label: String get() = "$name, $state"
}

object CityCatalog {
    val cities: List<City> = listOf(
        // Delhi NCR
        City("Delhi", "Delhi", 28.6139, 77.2090),
        City("Gurugram", "Haryana", 28.4595, 77.0266),
        City("Noida", "Uttar Pradesh", 28.5355, 77.3910),
        City("Ghaziabad", "Uttar Pradesh", 28.6692, 77.4538),
        City("Faridabad", "Haryana", 28.4089, 77.3178),
        // Haryana / Punjab / Chandigarh
        City("Chandigarh", "Chandigarh", 30.7333, 76.7794),
        City("Mohali", "Punjab", 30.7046, 76.7179),
        City("Panchkula", "Haryana", 30.6942, 76.8606),
        City("Ambala", "Haryana", 30.3782, 76.7767),
        City("Panipat", "Haryana", 29.3909, 76.9635),
        City("Karnal", "Haryana", 29.6857, 76.9905),
        City("Kurukshetra", "Haryana", 29.9695, 76.8783),
        City("Sonipat", "Haryana", 28.9931, 77.0151),
        City("Rohtak", "Haryana", 28.8955, 76.6066),
        City("Hisar", "Haryana", 29.1492, 75.7217),
        City("Ludhiana", "Punjab", 30.9010, 75.8573),
        City("Jalandhar", "Punjab", 31.3260, 75.5762),
        City("Amritsar", "Punjab", 31.6340, 74.8723),
        City("Patiala", "Punjab", 30.3398, 76.3869),
        City("Bathinda", "Punjab", 30.2110, 74.9455),
        // Himachal / Uttarakhand / J&K
        City("Shimla", "Himachal Pradesh", 31.1048, 77.1734),
        City("Solan", "Himachal Pradesh", 30.9045, 77.0967),
        City("Manali", "Himachal Pradesh", 32.2432, 77.1892),
        City("Kullu", "Himachal Pradesh", 31.9579, 77.1095),
        City("Dharamshala", "Himachal Pradesh", 32.2190, 76.3234),
        City("Dehradun", "Uttarakhand", 30.3165, 78.0322),
        City("Rishikesh", "Uttarakhand", 30.0869, 78.2676),
        City("Haridwar", "Uttarakhand", 29.9457, 78.1642),
        City("Mussoorie", "Uttarakhand", 30.4598, 78.0644),
        City("Roorkee", "Uttarakhand", 29.8543, 77.8880),
        City("Nainital", "Uttarakhand", 29.3803, 79.4636),
        City("Haldwani", "Uttarakhand", 29.2183, 79.5130),
        City("Jammu", "Jammu & Kashmir", 32.7266, 74.8570),
        City("Katra", "Jammu & Kashmir", 32.9916, 74.9318),
        City("Srinagar", "Jammu & Kashmir", 34.0837, 74.7973),
        // Uttar Pradesh
        City("Agra", "Uttar Pradesh", 27.1767, 78.0081),
        City("Mathura", "Uttar Pradesh", 27.4924, 77.6737),
        City("Vrindavan", "Uttar Pradesh", 27.5650, 77.6593),
        City("Aligarh", "Uttar Pradesh", 27.8974, 78.0880),
        City("Meerut", "Uttar Pradesh", 28.9845, 77.7064),
        City("Moradabad", "Uttar Pradesh", 28.8386, 78.7733),
        City("Bareilly", "Uttar Pradesh", 28.3670, 79.4304),
        City("Lucknow", "Uttar Pradesh", 26.8467, 80.9462),
        City("Kanpur", "Uttar Pradesh", 26.4499, 80.3319),
        City("Prayagraj", "Uttar Pradesh", 25.4358, 81.8463),
        City("Varanasi", "Uttar Pradesh", 25.3176, 82.9739),
        City("Ayodhya", "Uttar Pradesh", 26.7922, 82.1998),
        City("Gorakhpur", "Uttar Pradesh", 26.7606, 83.3732),
        City("Jhansi", "Uttar Pradesh", 25.4484, 78.5685),
        // Rajasthan
        City("Jaipur", "Rajasthan", 26.9124, 75.7873),
        City("Ajmer", "Rajasthan", 26.4499, 74.6399),
        City("Pushkar", "Rajasthan", 26.4897, 74.5511),
        City("Jodhpur", "Rajasthan", 26.2389, 73.0243),
        City("Udaipur", "Rajasthan", 24.5854, 73.7125),
        City("Kota", "Rajasthan", 25.2138, 75.8648),
        City("Bikaner", "Rajasthan", 28.0229, 73.3119),
        City("Jaisalmer", "Rajasthan", 26.9157, 70.9083),
        City("Mount Abu", "Rajasthan", 24.5926, 72.7156),
        // Gujarat
        City("Ahmedabad", "Gujarat", 23.0225, 72.5714),
        City("Gandhinagar", "Gujarat", 23.2156, 72.6369),
        City("Vadodara", "Gujarat", 22.3072, 73.1812),
        City("Surat", "Gujarat", 21.1702, 72.8311),
        City("Rajkot", "Gujarat", 22.3039, 70.8022),
        City("Bhavnagar", "Gujarat", 21.7645, 72.1519),
        City("Vapi", "Gujarat", 20.3893, 72.9106),
        City("Daman", "Dadra & Nagar Haveli and Daman & Diu", 20.3974, 72.8328),
        // Maharashtra / Goa
        City("Mumbai", "Maharashtra", 19.0760, 72.8777),
        City("Navi Mumbai", "Maharashtra", 19.0330, 73.0297),
        City("Thane", "Maharashtra", 19.2183, 72.9781),
        City("Pune", "Maharashtra", 18.5204, 73.8567),
        City("Lonavala", "Maharashtra", 18.7546, 73.4062),
        City("Nashik", "Maharashtra", 19.9975, 73.7898),
        City("Shirdi", "Maharashtra", 19.7645, 74.4762),
        City("Aurangabad", "Maharashtra", 19.8762, 75.3433),
        City("Ahmednagar", "Maharashtra", 19.0948, 74.7480),
        City("Nagpur", "Maharashtra", 21.1458, 79.0882),
        City("Kolhapur", "Maharashtra", 16.7050, 74.2433),
        City("Satara", "Maharashtra", 17.6805, 74.0183),
        City("Mahabaleshwar", "Maharashtra", 17.9237, 73.6586),
        City("Solapur", "Maharashtra", 17.6599, 75.9064),
        City("Ratnagiri", "Maharashtra", 16.9902, 73.3120),
        City("Alibaug", "Maharashtra", 18.6414, 72.8722),
        City("Panaji", "Goa", 15.4909, 73.8278),
        City("Margao", "Goa", 15.2832, 73.9862),
        // Karnataka
        City("Bengaluru", "Karnataka", 12.9716, 77.5946),
        City("Mysuru", "Karnataka", 12.2958, 76.6394),
        City("Mangaluru", "Karnataka", 12.9141, 74.8560),
        City("Udupi", "Karnataka", 13.3409, 74.7421),
        City("Madikeri (Coorg)", "Karnataka", 12.4244, 75.7382),
        City("Chikkamagaluru", "Karnataka", 13.3161, 75.7720),
        City("Hubballi", "Karnataka", 15.3647, 75.1240),
        City("Belagavi", "Karnataka", 15.8497, 74.4977),
        City("Hampi", "Karnataka", 15.3350, 76.4600),
        City("Davanagere", "Karnataka", 14.4644, 75.9218),
        City("Shivamogga", "Karnataka", 13.9299, 75.5681),
        City("Tumakuru", "Karnataka", 13.3379, 77.1173),
        // Tamil Nadu / Puducherry
        City("Chennai", "Tamil Nadu", 13.0827, 80.2707),
        City("Hosur", "Tamil Nadu", 12.7409, 77.8253),
        City("Vellore", "Tamil Nadu", 12.9165, 79.1325),
        City("Kanchipuram", "Tamil Nadu", 12.8342, 79.7036),
        City("Mahabalipuram", "Tamil Nadu", 12.6208, 80.1945),
        City("Puducherry", "Puducherry", 11.9416, 79.8083),
        City("Coimbatore", "Tamil Nadu", 11.0168, 76.9558),
        City("Ooty", "Tamil Nadu", 11.4102, 76.6950),
        City("Salem", "Tamil Nadu", 11.6643, 78.1460),
        City("Tiruchirappalli", "Tamil Nadu", 10.7905, 78.7047),
        City("Madurai", "Tamil Nadu", 9.9252, 78.1198),
        City("Kodaikanal", "Tamil Nadu", 10.2381, 77.4892),
        City("Tirunelveli", "Tamil Nadu", 8.7139, 77.7567),
        City("Kanyakumari", "Tamil Nadu", 8.0883, 77.5385),
        City("Rameswaram", "Tamil Nadu", 9.2876, 79.3129),
        // Kerala
        City("Kochi", "Kerala", 9.9312, 76.2673),
        City("Thiruvananthapuram", "Kerala", 8.5241, 76.9366),
        City("Alappuzha", "Kerala", 9.4981, 76.3388),
        City("Thrissur", "Kerala", 10.5276, 76.2144),
        City("Kozhikode", "Kerala", 11.2588, 75.7804),
        City("Munnar", "Kerala", 10.0889, 77.0595),
        // Telangana / Andhra Pradesh
        City("Hyderabad", "Telangana", 17.3850, 78.4867),
        City("Warangal", "Telangana", 17.9689, 79.5941),
        City("Karimnagar", "Telangana", 18.4386, 79.1288),
        City("Nizamabad", "Telangana", 18.6725, 78.0941),
        City("Vijayawada", "Andhra Pradesh", 16.5062, 80.6480),
        City("Guntur", "Andhra Pradesh", 16.3067, 80.4365),
        City("Visakhapatnam", "Andhra Pradesh", 17.6868, 83.2185),
        City("Rajahmundry", "Andhra Pradesh", 17.0005, 81.8040),
        City("Kakinada", "Andhra Pradesh", 16.9891, 82.2475),
        City("Tirupati", "Andhra Pradesh", 13.6288, 79.4192),
        City("Nellore", "Andhra Pradesh", 14.4426, 79.9865),
        City("Kurnool", "Andhra Pradesh", 15.8281, 78.0373),
        City("Anantapur", "Andhra Pradesh", 14.6819, 77.6006),
        // Madhya Pradesh / Chhattisgarh
        City("Bhopal", "Madhya Pradesh", 23.2599, 77.4126),
        City("Indore", "Madhya Pradesh", 22.7196, 75.8577),
        City("Ujjain", "Madhya Pradesh", 23.1765, 75.7885),
        City("Gwalior", "Madhya Pradesh", 26.2183, 78.1828),
        City("Jabalpur", "Madhya Pradesh", 23.1815, 79.9864),
        City("Raipur", "Chhattisgarh", 21.2514, 81.6296),
        // East & North-East
        City("Kolkata", "West Bengal", 22.5726, 88.3639),
        City("Howrah", "West Bengal", 22.5958, 88.2636),
        City("Durgapur", "West Bengal", 23.5204, 87.3119),
        City("Asansol", "West Bengal", 23.6739, 86.9524),
        City("Digha", "West Bengal", 21.6266, 87.5074),
        City("Siliguri", "West Bengal", 26.7271, 88.3953),
        City("Darjeeling", "West Bengal", 27.0410, 88.2663),
        City("Bhubaneswar", "Odisha", 20.2961, 85.8245),
        City("Cuttack", "Odisha", 20.4625, 85.8830),
        City("Puri", "Odisha", 19.8135, 85.8312),
        City("Patna", "Bihar", 25.5941, 85.1376),
        City("Gaya", "Bihar", 24.7914, 85.0002),
        City("Ranchi", "Jharkhand", 23.3441, 85.3096),
        City("Jamshedpur", "Jharkhand", 22.8046, 86.2029),
        City("Dhanbad", "Jharkhand", 23.7957, 86.4304),
        City("Guwahati", "Assam", 26.1445, 91.7362),
        City("Shillong", "Meghalaya", 25.5788, 91.8933),
    )

    private val byName = cities.associateBy { it.name.lowercase() }

    val popular: List<String> = listOf(
        "Delhi", "Mumbai", "Bengaluru", "Pune", "Jaipur", "Chennai", "Hyderabad", "Chandigarh",
        "Ahmedabad", "Kolkata", "Agra", "Mysuru", "Dehradun", "Lucknow", "Kochi", "Goa",
    )

    fun find(name: String): City? = byName[name.trim().lowercase()]
        ?: if (name.equals("Goa", true)) byName["panaji"] else null

    fun search(query: String, limit: Int = 30): List<City> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return popular.mapNotNull { find(it) }
        val starts = cities.filter { it.name.lowercase().startsWith(q) }
        val contains = cities.filter { it !in starts && (it.name.lowercase().contains(q) || it.state.lowercase().contains(q)) }
        return (starts + contains).take(limit)
    }

    /** Cities close to the straight line between [a] and [b], ordered from a to b. */
    fun citiesOnTheWay(a: City, b: City, maxDetourKm: Double = 35.0): List<City> {
        val total = Geo.distanceKm(a.lat, a.lng, b.lat, b.lng)
        if (total < 60) return emptyList()
        return cities.asSequence()
            .filter { it != a && it != b }
            .mapNotNull { c ->
                val da = Geo.distanceKm(a.lat, a.lng, c.lat, c.lng)
                val db = Geo.distanceKm(c.lat, c.lng, b.lat, b.lng)
                val detour = da + db - total
                if (detour <= maxDetourKm && da > 25 && db > 25) c to da else null
            }
            .sortedBy { it.second }
            .map { it.first }
            .toList()
    }

    fun pickupSuggestions(city: String): List<String> {
        val special = landmarks[city].orEmpty()
        return special + listOf(
            "$city Railway Station",
            "$city Bus Stand",
            "$city City Centre",
            "$city Airport",
        )
    }

    private val landmarks = mapOf(
        "Delhi" to listOf("Kashmere Gate ISBT", "Anand Vihar ISBT", "Rajiv Chowk Metro, Connaught Place", "Dhaula Kuan", "Akshardham Metro Station"),
        "Gurugram" to listOf("IFFCO Chowk Metro", "Cyber City", "Rajiv Chowk, Gurugram", "Huda City Centre Metro"),
        "Noida" to listOf("Sector 18 Metro Station", "Botanical Garden Metro", "Pari Chowk, Greater Noida"),
        "Mumbai" to listOf("Dadar East", "Andheri East (Western Express Hwy)", "Sion Circle", "Vashi Bridge", "Bandra Kurla Complex"),
        "Pune" to listOf("Shivajinagar Bus Stand", "Hinjewadi Phase 1", "Swargate", "Wakad Bridge", "Chandni Chowk, Kothrud"),
        "Bengaluru" to listOf("Majestic (Kempegowda Bus Station)", "Silk Board Junction", "Hebbal Flyover", "Electronic City Toll", "Mysore Road Satellite Bus Stand"),
        "Chennai" to listOf("Koyambedu Bus Terminus", "Guindy", "Tambaram", "Chennai Central"),
        "Hyderabad" to listOf("Miyapur Metro", "LB Nagar", "Hitech City", "Secunderabad Station", "Shamshabad (RGIA)"),
        "Jaipur" to listOf("Sindhi Camp Bus Stand", "Ajmeri Gate", "Durgapura", "Malviya Nagar"),
        "Chandigarh" to listOf("ISBT Sector 43", "ISBT Sector 17", "Tribune Chowk", "Zirakpur Flyover"),
        "Kolkata" to listOf("Esplanade", "Ultadanga", "Salt Lake Sector V", "Howrah Bridge"),
        "Ahmedabad" to listOf("Paldi", "Iskcon Cross Road", "Geeta Mandir Bus Stand", "Nehru Nagar"),
        "Dehradun" to listOf("ISBT Dehradun", "Clock Tower (Ghanta Ghar)", "Rajpur Road"),
        "Agra" to listOf("Idgah Bus Stand", "Taj Mahal East Gate", "Agra Cantt Station"),
        "Mysuru" to listOf("Mysuru Palace", "Suburban Bus Stand", "Infosys Campus Gate"),
    )
}

object Geo {
    private const val EARTH_KM = 6371.0
    /** Rough ratio between road distance and great-circle distance in India. */
    private const val ROAD_FACTOR = 1.22
    private const val AVG_SPEED_KMH = 55.0

    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_KM * asin(sqrt(a))
    }

    fun roadKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int =
        (distanceKm(lat1, lng1, lat2, lng2) * ROAD_FACTOR).roundToInt().coerceAtLeast(1)

    fun durationMin(roadKm: Int): Int {
        val raw = roadKm / AVG_SPEED_KMH * 60.0 + 10
        return ((raw / 5.0).roundToInt() * 5).coerceAtLeast(15)
    }
}

object Pricing {
    /** Recommended price per seat in INR for a given road distance. */
    fun recommended(km: Int): Int = (((km * 1.6) / 10.0).roundToInt() * 10).coerceAtLeast(50)

    fun min(km: Int): Int = ((recommended(km) * 0.5) / 10.0).roundToInt().times(10).coerceAtLeast(30)

    fun max(km: Int): Int = ((recommended(km) * 1.5) / 10.0).roundToInt().times(10).coerceAtLeast(80)

    /** Passenger service fee charged on top of the seat price. */
    fun serviceFee(subtotal: Int): Int = ((subtotal * 0.08).roundToInt()).coerceAtLeast(10)

    /** Share of the full price for a partial segment of a ride. */
    fun segmentPrice(fullPrice: Int, segmentKm: Int, totalKm: Int): Int {
        if (totalKm <= 0 || segmentKm >= totalKm) return fullPrice
        val raw = fullPrice * segmentKm.toDouble() / totalKm
        return ((raw / 10.0).roundToInt() * 10).coerceIn(30, fullPrice)
    }
}

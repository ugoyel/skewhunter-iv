package com.carsathi.app.data.catalog

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class City(val name: String, val state: String, val lat: Double, val lng: Double) {
    val label: String get() = "$name, $state"
}

/** A named pickup / drop-off spot inside a city. */
data class MeetingPoint(val label: String, val lat: Double, val lng: Double)

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
            .filter { it.name != a.name && it.name != b.name }
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

    /** Popular meeting points in [city] with their coordinates, plus generic central points. */
    fun meetingPoints(city: City): List<MeetingPoint> {
        val special = landmarks[city.name].orEmpty()
        val generic = listOf(
            MeetingPoint("${city.name} City Centre", city.lat, city.lng),
            MeetingPoint("${city.name} Railway Station", city.lat, city.lng),
            MeetingPoint("${city.name} Bus Stand", city.lat, city.lng),
        ).filter { g -> special.none { it.label.equals(g.label, true) } }
        return special + generic
    }

    fun pickupSuggestions(city: String): List<String> =
        find(city)?.let { c -> meetingPoints(c).map { it.label } } ?: listOf(city)

    /** The city with its coordinates moved to the named meeting point (falls back to the city centre). */
    fun point(cityName: String, label: String): City {
        val c = find(cityName)!!
        val p = meetingPoints(c).firstOrNull { it.label == label } ?: return c
        return c.copy(lat = p.lat, lng = p.lng)
    }

    /** Closest catalogue city to a coordinate and its distance in km. */
    fun nearest(lat: Double, lng: Double): Pair<City, Double> =
        cities.map { it to Geo.distanceKm(lat, lng, it.lat, it.lng) }.minBy { it.second }

    /** Closest known meeting point in [city] to a coordinate, if within [maxKm]. */
    fun nearestMeetingPoint(city: City, lat: Double, lng: Double, maxKm: Double): MeetingPoint? =
        landmarks[city.name].orEmpty()
            .map { it to Geo.distanceKm(lat, lng, it.lat, it.lng) }
            .filter { it.second <= maxKm }
            .minByOrNull { it.second }?.first

    private val landmarks: Map<String, List<MeetingPoint>> = mapOf(
        "Delhi" to listOf(
            MeetingPoint("Kashmere Gate ISBT", 28.6676, 77.2280),
            MeetingPoint("Anand Vihar ISBT", 28.6469, 77.3159),
            MeetingPoint("Rajiv Chowk Metro, Connaught Place", 28.6328, 77.2197),
            MeetingPoint("Dhaula Kuan", 28.5918, 77.1615),
            MeetingPoint("Akshardham Metro Station", 28.6185, 77.2795),
            MeetingPoint("New Delhi Railway Station", 28.6430, 77.2194),
            MeetingPoint("Saket Metro Station", 28.5206, 77.2015),
        ),
        "Gurugram" to listOf(
            MeetingPoint("IFFCO Chowk Metro", 28.4723, 77.0724),
            MeetingPoint("Cyber City", 28.4950, 77.0890),
            MeetingPoint("Millennium City Centre (HUDA) Metro", 28.4593, 77.0727),
            MeetingPoint("Rajiv Chowk, Gurugram", 28.4473, 77.0400),
        ),
        "Noida" to listOf(
            MeetingPoint("Sector 18 Metro Station", 28.5708, 77.3261),
            MeetingPoint("Botanical Garden Metro", 28.5641, 77.3341),
            MeetingPoint("Pari Chowk, Greater Noida", 28.4652, 77.5110),
        ),
        "Mumbai" to listOf(
            MeetingPoint("Dadar East", 19.0178, 72.8478),
            MeetingPoint("Andheri East (Western Express Hwy)", 19.1136, 72.8697),
            MeetingPoint("Sion Circle", 19.0434, 72.8626),
            MeetingPoint("Vashi Bridge", 19.0771, 72.9986),
            MeetingPoint("Bandra Kurla Complex", 19.0660, 72.8680),
        ),
        "Pune" to listOf(
            MeetingPoint("Shivajinagar Bus Stand", 18.5308, 73.8475),
            MeetingPoint("Hinjewadi Phase 1", 18.5913, 73.7389),
            MeetingPoint("Swargate", 18.5018, 73.8636),
            MeetingPoint("Wakad Bridge", 18.5980, 73.7650),
            MeetingPoint("Chandni Chowk, Kothrud", 18.5095, 73.7880),
        ),
        "Bengaluru" to listOf(
            MeetingPoint("Majestic (Kempegowda Bus Station)", 12.9767, 77.5713),
            MeetingPoint("Silk Board Junction", 12.9172, 77.6229),
            MeetingPoint("Hebbal Flyover", 13.0358, 77.5970),
            MeetingPoint("Electronic City Toll", 12.8452, 77.6602),
            MeetingPoint("Mysore Road Satellite Bus Stand", 12.9530, 77.5430),
        ),
        "Chennai" to listOf(
            MeetingPoint("Koyambedu Bus Terminus", 13.0694, 80.1948),
            MeetingPoint("Guindy", 13.0067, 80.2206),
            MeetingPoint("Tambaram", 12.9249, 80.1000),
            MeetingPoint("Chennai Central", 13.0827, 80.2752),
        ),
        "Hyderabad" to listOf(
            MeetingPoint("Miyapur Metro", 17.4969, 78.3725),
            MeetingPoint("LB Nagar", 17.3457, 78.5522),
            MeetingPoint("Hitech City", 17.4435, 78.3772),
            MeetingPoint("Secunderabad Station", 17.4337, 78.5016),
            MeetingPoint("Shamshabad (RGIA)", 17.2403, 78.4294),
        ),
        "Jaipur" to listOf(
            MeetingPoint("Sindhi Camp Bus Stand", 26.9239, 75.7997),
            MeetingPoint("Ajmeri Gate", 26.9157, 75.8175),
            MeetingPoint("Durgapura", 26.8530, 75.7930),
            MeetingPoint("Malviya Nagar", 26.8549, 75.8243),
        ),
        "Chandigarh" to listOf(
            MeetingPoint("ISBT Sector 43", 30.7224, 76.7486),
            MeetingPoint("ISBT Sector 17", 30.7395, 76.7826),
            MeetingPoint("Tribune Chowk", 30.7270, 76.8006),
            MeetingPoint("Zirakpur Flyover", 30.6425, 76.8173),
        ),
        "Kolkata" to listOf(
            MeetingPoint("Esplanade", 22.5646, 88.3513),
            MeetingPoint("Ultadanga", 22.5886, 88.3906),
            MeetingPoint("Salt Lake Sector V", 22.5726, 88.4339),
            MeetingPoint("Howrah Bridge", 22.5851, 88.3468),
        ),
        "Ahmedabad" to listOf(
            MeetingPoint("Paldi", 23.0120, 72.5622),
            MeetingPoint("Iskcon Cross Road", 23.0260, 72.5070),
            MeetingPoint("Geeta Mandir Bus Stand", 23.0137, 72.5913),
            MeetingPoint("Nehru Nagar", 23.0210, 72.5480),
        ),
        "Dehradun" to listOf(
            MeetingPoint("ISBT Dehradun", 30.2850, 78.0010),
            MeetingPoint("Clock Tower (Ghanta Ghar)", 30.3245, 78.0418),
            MeetingPoint("Rajpur Road", 30.3400, 78.0600),
        ),
        "Agra" to listOf(
            MeetingPoint("Idgah Bus Stand", 27.1640, 77.9980),
            MeetingPoint("Taj Mahal East Gate", 27.1730, 78.0480),
            MeetingPoint("Agra Cantt Station", 27.1580, 77.9910),
        ),
        "Mysuru" to listOf(
            MeetingPoint("Mysuru Palace", 12.3052, 76.6552),
            MeetingPoint("Suburban Bus Stand", 12.3080, 76.6580),
            MeetingPoint("Infosys Campus Gate", 12.3560, 76.5940),
        ),
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

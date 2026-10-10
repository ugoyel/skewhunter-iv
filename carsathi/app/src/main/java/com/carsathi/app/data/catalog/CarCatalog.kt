package com.carsathi.app.data.catalog

import androidx.compose.ui.graphics.Color

data class CarModel(val name: String, val bodyType: String)

data class CarColor(val name: String, val color: Color)

/**
 * Bundled car make & model database (Indian market focused, plus common imports).
 * Body types: Hatchback, Sedan, SUV, Compact SUV, MUV, Van, Coupe, Convertible, Pickup.
 */
object CarCatalog {
    private const val H = "Hatchback"
    private const val S = "Sedan"
    private const val SUV = "SUV"
    private const val CSUV = "Compact SUV"
    private const val MUV = "MUV"
    private const val VAN = "Van"
    private const val COUPE = "Coupe"
    private const val CONV = "Convertible"
    private const val PICKUP = "Pickup"

    val bodyTypes = listOf(H, S, CSUV, SUV, MUV, VAN, COUPE, CONV, PICKUP)

    val fuelTypes = listOf("Petrol", "Diesel", "CNG", "Electric", "Hybrid", "LPG")

    val colors = listOf(
        CarColor("White", Color(0xFFFFFFFF)),
        CarColor("Black", Color(0xFF111111)),
        CarColor("Silver", Color(0xFFC0C6CA)),
        CarColor("Grey", Color(0xFF8A9499)),
        CarColor("Dark grey", Color(0xFF4A5357)),
        CarColor("Red", Color(0xFFD7263D)),
        CarColor("Maroon", Color(0xFF7B1E2B)),
        CarColor("Blue", Color(0xFF1E6FD9)),
        CarColor("Dark blue", Color(0xFF173A6B)),
        CarColor("Green", Color(0xFF2E9E5B)),
        CarColor("Dark green", Color(0xFF1F4D36)),
        CarColor("Brown", Color(0xFF6B4A2F)),
        CarColor("Beige", Color(0xFFE3D3B0)),
        CarColor("Orange", Color(0xFFF28C28)),
        CarColor("Yellow", Color(0xFFF5C518)),
        CarColor("Purple", Color(0xFF6A3D9A)),
        CarColor("Gold", Color(0xFFC9A54C)),
    )

    fun colorOf(name: String): Color = colors.firstOrNull { it.name.equals(name, true) }?.color ?: Color(0xFF8A9499)

    val brands: Map<String, List<CarModel>> = linkedMapOf(
        "Maruti Suzuki" to listOf(
            CarModel("Alto 800", H), CarModel("Alto K10", H), CarModel("S-Presso", H), CarModel("Celerio", H),
            CarModel("Wagon R", H), CarModel("Swift", H), CarModel("Dzire", S), CarModel("Baleno", H),
            CarModel("Ignis", H), CarModel("Ritz", H), CarModel("Ciaz", S), CarModel("Ertiga", MUV),
            CarModel("XL6", MUV), CarModel("Brezza", CSUV), CarModel("Fronx", CSUV), CarModel("Grand Vitara", SUV),
            CarModel("Jimny", SUV), CarModel("Invicto", MUV), CarModel("Eeco", VAN), CarModel("Omni", VAN),
            CarModel("SX4", S), CarModel("Esteem", S), CarModel("Zen Estilo", H), CarModel("A-Star", H),
            CarModel("Gypsy", SUV), CarModel("S-Cross", CSUV), CarModel("e Vitara", SUV),
        ),
        "Hyundai" to listOf(
            CarModel("Santro", H), CarModel("Eon", H), CarModel("i10", H), CarModel("Grand i10", H),
            CarModel("Grand i10 Nios", H), CarModel("i20", H), CarModel("i20 N Line", H), CarModel("Aura", S),
            CarModel("Xcent", S), CarModel("Accent", S), CarModel("Verna", S), CarModel("Elantra", S),
            CarModel("Exter", CSUV), CarModel("Venue", CSUV), CarModel("Venue N Line", CSUV), CarModel("Creta", SUV),
            CarModel("Creta N Line", SUV), CarModel("Creta Electric", SUV), CarModel("Alcazar", SUV),
            CarModel("Tucson", SUV), CarModel("Kona Electric", SUV), CarModel("Ioniq 5", SUV),
        ),
        "Tata" to listOf(
            CarModel("Nano", H), CarModel("Indica", H), CarModel("Bolt", H), CarModel("Tiago", H),
            CarModel("Tiago EV", H), CarModel("Altroz", H), CarModel("Indigo", S), CarModel("Zest", S),
            CarModel("Tigor", S), CarModel("Tigor EV", S), CarModel("Punch", CSUV), CarModel("Punch EV", CSUV),
            CarModel("Nexon", CSUV), CarModel("Nexon EV", CSUV), CarModel("Curvv", SUV), CarModel("Curvv EV", SUV),
            CarModel("Harrier", SUV), CarModel("Harrier EV", SUV), CarModel("Safari", SUV), CarModel("Hexa", SUV),
            CarModel("Sumo", MUV), CarModel("Aria", MUV),
        ),
        "Mahindra" to listOf(
            CarModel("KUV100", CSUV), CarModel("XUV300", CSUV), CarModel("XUV 3XO", CSUV), CarModel("XUV400 EV", CSUV),
            CarModel("TUV300", CSUV), CarModel("Bolero", SUV), CarModel("Bolero Neo", SUV), CarModel("Thar", SUV),
            CarModel("Thar Roxx", SUV), CarModel("Scorpio Classic", SUV), CarModel("Scorpio-N", SUV),
            CarModel("XUV500", SUV), CarModel("XUV700", SUV), CarModel("Alturas G4", SUV), CarModel("BE 6", SUV),
            CarModel("XEV 9e", SUV), CarModel("Marazzo", MUV), CarModel("Xylo", MUV), CarModel("Quanto", CSUV),
            CarModel("Scorpio Pik-Up", PICKUP),
        ),
        "Toyota" to listOf(
            CarModel("Etios Liva", H), CarModel("Glanza", H), CarModel("Etios", S), CarModel("Yaris", S),
            CarModel("Corolla Altis", S), CarModel("Camry", S), CarModel("Urban Cruiser Taisor", CSUV),
            CarModel("Urban Cruiser Hyryder", SUV), CarModel("Fortuner", SUV), CarModel("Fortuner Legender", SUV),
            CarModel("Land Cruiser", SUV), CarModel("Innova", MUV), CarModel("Innova Crysta", MUV),
            CarModel("Innova Hycross", MUV), CarModel("Rumion", MUV), CarModel("Vellfire", MUV), CarModel("Hilux", PICKUP),
        ),
        "Honda" to listOf(
            CarModel("Brio", H), CarModel("Jazz", H), CarModel("Amaze", S), CarModel("City", S),
            CarModel("City e:HEV", S), CarModel("Civic", S), CarModel("Accord", S), CarModel("WR-V", CSUV),
            CarModel("Elevate", SUV), CarModel("BR-V", MUV), CarModel("Mobilio", MUV), CarModel("CR-V", SUV),
        ),
        "Kia" to listOf(
            CarModel("Sonet", CSUV), CarModel("Syros", CSUV), CarModel("Seltos", SUV), CarModel("Carens", MUV),
            CarModel("Carnival", MUV), CarModel("EV6", SUV), CarModel("EV9", SUV),
        ),
        "MG" to listOf(
            CarModel("Comet EV", H), CarModel("Astor", SUV), CarModel("ZS EV", SUV), CarModel("Hector", SUV),
            CarModel("Hector Plus", SUV), CarModel("Windsor EV", CSUV), CarModel("Gloster", SUV),
        ),
        "Renault" to listOf(
            CarModel("Kwid", H), CarModel("Pulse", H), CarModel("Scala", S), CarModel("Triber", MUV),
            CarModel("Kiger", CSUV), CarModel("Duster", SUV), CarModel("Captur", SUV), CarModel("Lodgy", MUV),
        ),
        "Nissan" to listOf(
            CarModel("Micra", H), CarModel("Sunny", S), CarModel("Magnite", CSUV), CarModel("Kicks", SUV),
            CarModel("Terrano", SUV), CarModel("X-Trail", SUV),
        ),
        "Skoda" to listOf(
            CarModel("Fabia", H), CarModel("Rapid", S), CarModel("Slavia", S), CarModel("Octavia", S),
            CarModel("Laura", S), CarModel("Superb", S), CarModel("Kylaq", CSUV), CarModel("Kushaq", SUV),
            CarModel("Kodiaq", SUV),
        ),
        "Volkswagen" to listOf(
            CarModel("Polo", H), CarModel("Ameo", S), CarModel("Vento", S), CarModel("Virtus", S),
            CarModel("Jetta", S), CarModel("Passat", S), CarModel("Taigun", SUV), CarModel("Tiguan", SUV),
        ),
        "Ford" to listOf(
            CarModel("Figo", H), CarModel("Freestyle", H), CarModel("Figo Aspire", S), CarModel("Fiesta", S),
            CarModel("Ikon", S), CarModel("EcoSport", CSUV), CarModel("Endeavour", SUV),
        ),
        "Jeep" to listOf(
            CarModel("Compass", SUV), CarModel("Meridian", SUV), CarModel("Wrangler", SUV), CarModel("Grand Cherokee", SUV),
        ),
        "Citroën" to listOf(
            CarModel("C3", H), CarModel("eC3", H), CarModel("Basalt", COUPE), CarModel("C3 Aircross", SUV),
            CarModel("C5 Aircross", SUV),
        ),
        "BYD" to listOf(
            CarModel("Atto 3", SUV), CarModel("Seal", S), CarModel("Sealion 7", SUV), CarModel("e6", MUV), CarModel("eMAX 7", MUV),
        ),
        "Chevrolet" to listOf(
            CarModel("Spark", H), CarModel("Beat", H), CarModel("Aveo", S), CarModel("Sail", S), CarModel("Cruze", S),
            CarModel("Enjoy", MUV), CarModel("Tavera", MUV), CarModel("Captiva", SUV),
        ),
        "Fiat" to listOf(CarModel("Punto", H), CarModel("Palio", H), CarModel("Linea", S)),
        "Datsun" to listOf(CarModel("redi-GO", H), CarModel("GO", H), CarModel("GO+", MUV)),
        "Force" to listOf(CarModel("Gurkha", SUV), CarModel("Trax Cruiser", VAN)),
        "Isuzu" to listOf(CarModel("D-Max V-Cross", PICKUP), CarModel("MU-X", SUV)),
        "Mitsubishi" to listOf(CarModel("Lancer", S), CarModel("Outlander", SUV), CarModel("Pajero Sport", SUV)),
        "Hindustan Motors" to listOf(CarModel("Ambassador", S)),
        "BMW" to listOf(
            CarModel("2 Series Gran Coupe", S), CarModel("3 Series", S), CarModel("5 Series", S), CarModel("7 Series", S),
            CarModel("i4", S), CarModel("X1", SUV), CarModel("X3", SUV), CarModel("X5", SUV), CarModel("X7", SUV),
            CarModel("iX", SUV), CarModel("Z4", CONV),
        ),
        "Mercedes-Benz" to listOf(
            CarModel("A-Class Limousine", S), CarModel("C-Class", S), CarModel("E-Class", S), CarModel("S-Class", S),
            CarModel("EQS", S), CarModel("GLA", SUV), CarModel("GLC", SUV), CarModel("GLE", SUV), CarModel("GLS", SUV),
            CarModel("G-Class", SUV),
        ),
        "Audi" to listOf(
            CarModel("A4", S), CarModel("A6", S), CarModel("A8", S), CarModel("Q3", SUV), CarModel("Q5", SUV),
            CarModel("Q7", SUV), CarModel("Q8", SUV), CarModel("e-tron", SUV),
        ),
        "Volvo" to listOf(CarModel("S60", S), CarModel("S90", S), CarModel("XC40", SUV), CarModel("XC60", SUV), CarModel("XC90", SUV)),
        "Lexus" to listOf(CarModel("ES", S), CarModel("NX", SUV), CarModel("RX", SUV), CarModel("LX", SUV), CarModel("LM", MUV)),
        "Jaguar" to listOf(CarModel("XE", S), CarModel("XF", S), CarModel("F-Pace", SUV), CarModel("F-Type", COUPE)),
        "Land Rover" to listOf(
            CarModel("Defender", SUV), CarModel("Discovery Sport", SUV), CarModel("Range Rover Evoque", SUV),
            CarModel("Range Rover Velar", SUV), CarModel("Range Rover Sport", SUV), CarModel("Range Rover", SUV),
        ),
        "Mini" to listOf(CarModel("Cooper", H), CarModel("Cooper Convertible", CONV), CarModel("Countryman", SUV)),
        "Porsche" to listOf(
            CarModel("911", COUPE), CarModel("Panamera", S), CarModel("Taycan", S), CarModel("Macan", SUV), CarModel("Cayenne", SUV),
        ),
        "Tesla" to listOf(CarModel("Model 3", S), CarModel("Model Y", SUV)),
    )

    val brandNames: List<String> = brands.keys.toList()

    fun models(brand: String): List<CarModel> = brands[brand].orEmpty()

    fun bodyTypeFor(brand: String, model: String): String? = brands[brand]?.firstOrNull { it.name == model }?.bodyType

    fun seatsFor(bodyType: String): Int = when (bodyType) {
        MUV, VAN -> 6
        SUV -> 4
        COUPE, CONV -> 1
        PICKUP -> 3
        else -> 3
    }

    val totalModels: Int get() = brands.values.sumOf { it.size }
}

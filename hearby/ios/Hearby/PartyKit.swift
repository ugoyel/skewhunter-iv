import SwiftUI

/// Hearby's look: a night-club palette on a dark stage. Neon pink, violet and electric blue carry
/// the brand; lime means "locked in", sun means "heads up", coral means "stop".
/// The same design system as android/.../ui/PartyKit.kt.
enum Party {
    static let night = Color(rgb: 0x0D0620)
    static let pink = Color(rgb: 0xFF3D9A)
    static let violet = Color(rgb: 0x8B5CFF)
    static let blue = Color(rgb: 0x29C5FF)
    static let lime = Color(rgb: 0xC8FF3D)
    static let sun = Color(rgb: 0xFFC53D)
    static let coral = Color(rgb: 0xFF6B5E)

    static let inkSoft = Color.white.opacity(0.74)
    static let inkFaint = Color.white.opacity(0.5)
    static let glass = Color.white.opacity(0.07)
    static let glassBright = Color.white.opacity(0.11)
    static let glassStroke = Color.white.opacity(0.14)

    static let brand = LinearGradient(colors: [pink, violet, blue], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let brandSoft = LinearGradient(
        colors: [pink.opacity(0.34), violet.opacity(0.3), blue.opacity(0.3)], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let stop = LinearGradient(
        colors: [Color(rgb: 0xFF4D6D), coral, Color(rgb: 0xFF9E57)], startPoint: .leading, endPoint: .trailing)

    /// SF Rounded: the system's friendly, bouncy face.
    static func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }

    /// Pink to violet to blue, for position `f` in 0...1 (used across the visualizer bars).
    static func spectrum(_ f: Double) -> Color {
        let stops: [(Double, Double, Double)] = [(1.0, 0.239, 0.604), (0.545, 0.361, 1.0), (0.161, 0.773, 1.0)]
        let x = min(max(f, 0), 1) * 2
        let i = min(Int(x), 1)
        let t = x - Double(i)
        let a = stops[i]
        let b = stops[i + 1]
        return Color(red: a.0 + (b.0 - a.0) * t, green: a.1 + (b.1 - a.1) * t, blue: a.2 + (b.2 - a.2) * t)
    }
}

extension Color {
    init(rgb: UInt32) {
        self.init(
            red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}

// MARK: - Stage

/// The stage: a dark backdrop with three slow-drifting pools of coloured light. `energy` (0...1)
/// turns the lights up when a party is actually playing.
struct PartyBackground: View {
    let energy: Double
    @Environment(\.accessibilityReduceMotion) var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
            Canvas { context, size in
                let drift = reduceMotion ? 0.12 : timeline.date.timeIntervalSinceReferenceDate / 28.0
                let a = drift * 2 * Double.pi
                let glow = 0.34 + 0.24 * min(max(energy, 0), 1)
                func light(_ color: Color, _ cx: Double, _ cy: Double, _ r: Double) {
                    let center = CGPoint(x: cx, y: cy)
                    let rect = CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r)
                    context.fill(
                        Path(ellipseIn: rect),
                        with: .radialGradient(
                            Gradient(colors: [color.opacity(glow), color.opacity(0)]), center: center, startRadius: 0, endRadius: r))
                }
                let w = Double(size.width)
                let h = Double(size.height)
                light(Party.pink, w * (0.08 + 0.10 * cos(a)), h * (0.06 + 0.05 * sin(a)), w * 0.85)
                light(Party.blue, w * (1.00 + 0.08 * sin(a * 2)), h * (0.40 + 0.08 * cos(a)), w * 0.75)
                light(Party.violet, w * (0.28 + 0.14 * sin(a + 1)), h * (1.00 + 0.04 * cos(a * 2)), w * 0.95)
            }
        }
        .background(Party.night)
        .ignoresSafeArea()
    }
}

// MARK: - Containers

/// A frosted panel floating over the stage.
struct GlassCard<Content: View>: View {
    var lit = false
    @ViewBuilder var content: Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 28, style: .continuous)
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(shape.fill(lit ? Party.glassBright : Party.glass))
            .overlay(
                shape.strokeBorder(
                    lit ? AnyShapeStyle(LinearGradient(colors: [Party.pink.opacity(0.8), Party.blue.opacity(0.8)], startPoint: .topLeading, endPoint: .bottomTrailing))
                        : AnyShapeStyle(Party.glassStroke),
                    lineWidth: 1))
    }
}

/// A heads-up panel.
struct Notice: View {
    let text: String
    var symbol = "sparkles"
    var color = Party.sun

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).foregroundColor(color)
            Text(text).font(Party.font(15)).foregroundColor(.white)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(shape.fill(color.opacity(0.12)))
        .overlay(shape.strokeBorder(color.opacity(0.4), lineWidth: 1))
    }
}

// MARK: - Buttons

/// The big call-to-action: a glowing gradient pill that squishes when pressed.
struct PartyButtonStyle: ButtonStyle {
    var fill = Party.brand
    var glow = Party.pink
    var height: CGFloat = 60
    var fullWidth = true

    func makeBody(configuration: Configuration) -> some View {
        Pill(configuration: configuration, fill: fill, glow: glow, height: height, fullWidth: fullWidth)
    }

    // `isEnabled` is only reliably readable from a View, not from the style itself.
    private struct Pill: View {
        let configuration: ButtonStyleConfiguration
        let fill: LinearGradient
        let glow: Color
        let height: CGFloat
        let fullWidth: Bool
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            configuration.label
                .font(Party.font(18, .semibold))
                .lineLimit(1)
                .foregroundColor(isEnabled ? .white : Party.inkFaint)
                .padding(.horizontal, 22)
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
                .background(Capsule().fill(isEnabled ? AnyShapeStyle(fill) : AnyShapeStyle(Color.white.opacity(0.10))))
                .shadow(color: isEnabled ? glow.opacity(0.55) : .clear, radius: 14, y: 6)
                .scaleEffect(configuration.isPressed ? 0.95 : 1)
                .animation(.spring(response: 0.25, dampingFraction: 0.5), value: configuration.isPressed)
        }
    }
}

/// Secondary action: a see-through pill.
struct GhostButtonStyle: ButtonStyle {
    var height: CGFloat = 56
    var fullWidth = true

    func makeBody(configuration: Configuration) -> some View {
        Pill(configuration: configuration, height: height, fullWidth: fullWidth)
    }

    private struct Pill: View {
        let configuration: ButtonStyleConfiguration
        let height: CGFloat
        let fullWidth: Bool
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            configuration.label
                .font(Party.font(16, .semibold))
                .lineLimit(1)
                .foregroundColor(isEnabled ? .white : Party.inkFaint)
                .padding(.horizontal, 14)
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
                .background(Capsule().fill(configuration.isPressed ? Party.glassBright : Party.glass))
                .overlay(Capsule().strokeBorder(Party.glassStroke, lineWidth: 1))
        }
    }
}

// MARK: - Motion

/// The glowing disc at the top of every screen. While `active` it throws out rings like a speaker
/// cone, and it bumps with the music (`level`, 0...1).
struct PulseOrb: View {
    let symbol: String
    let active: Bool
    var level: Float = 0
    var size: CGFloat = 60
    @Environment(\.accessibilityReduceMotion) var reduceMotion

    var body: some View {
        ZStack {
            if active {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
                    Canvas { context, canvas in
                        let ring = reduceMotion ? 0.3 : timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.9) / 1.9
                        let core = Double(size) / 2
                        let center = CGPoint(x: canvas.width / 2, y: canvas.height / 2)
                        for k in 0..<2 {
                            let p = (ring + Double(k) * 0.5).truncatingRemainder(dividingBy: 1)
                            let r = core * (1 + 0.5 * p)
                            let rect = CGRect(x: Double(center.x) - r, y: Double(center.y) - r, width: 2 * r, height: 2 * r)
                            context.stroke(Path(ellipseIn: rect), with: .color(Party.spectrum(p).opacity((1 - p) * 0.55)), lineWidth: 3)
                        }
                    }
                }
            }
            Circle()
                .fill(active ? Party.brand : Party.brandSoft)
                .overlay(Circle().strokeBorder(Color.white.opacity(active ? 0.35 : 0.22), lineWidth: 1))
                .frame(width: size, height: size)
                .shadow(color: active ? Party.pink.opacity(0.6) : .clear, radius: 14)
                .overlay(
                    Image(systemName: symbol)
                        .font(.system(size: size * 0.4, weight: .semibold))
                        .foregroundColor(.white))
                .scaleEffect(1 + 0.14 * CGFloat(min(max(level, 0), 1).squareRoot()))
                .animation(.spring(response: 0.25, dampingFraction: 0.45), value: level)
        }
        .frame(width: size * 1.5, height: size * 1.5)
    }
}

/// A row of dancing bars driven by the real audio level, so it only moves when music does. With no
/// sound the bars settle into a row of dots.
struct Equalizer: View {
    let level: Float
    var bars = 26
    @Environment(\.accessibilityReduceMotion) var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
            Canvas { context, size in
                let phase = reduceMotion ? 1.3
                    : timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2.6) / 2.6 * 2 * Double.pi
                let gap = 4.0
                let w = (Double(size.width) - gap * Double(bars - 1)) / Double(bars)
                let energy = Double(min(max(level, 0), 1)).squareRoot() // quiet passages should still dance
                for i in 0..<bars {
                    let wobble = 0.5 + 0.5 * sin(phase * Double(1 + i % 3) + Double(i) * 1.7)
                    let h = max(w, Double(size.height) * energy * (0.34 + 0.66 * wobble))
                    let rect = CGRect(x: Double(i) * (w + gap), y: (Double(size.height) - h) / 2, width: w, height: h)
                    context.fill(Path(roundedRect: rect, cornerRadius: w / 2), with: .color(Party.spectrum(Double(i) / Double(bars - 1))))
                }
            }
        }
        .frame(height: 52)
    }
}

/// "Searching": a dot sending out sonar rings.
struct Radar: View {
    var size: CGFloat = 22
    @Environment(\.accessibilityReduceMotion) var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
            Canvas { context, canvas in
                let sweep = reduceMotion ? 0.45 : timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.5) / 1.5
                let r = Double(min(canvas.width, canvas.height)) / 2
                let c = CGPoint(x: canvas.width / 2, y: canvas.height / 2)
                func circle(_ radius: Double) -> Path {
                    Path(ellipseIn: CGRect(x: Double(c.x) - radius, y: Double(c.y) - radius, width: 2 * radius, height: 2 * radius))
                }
                context.fill(circle(r * 0.22), with: .color(Party.blue))
                for k in 0..<2 {
                    let p = (sweep + Double(k) * 0.5).truncatingRemainder(dividingBy: 1)
                    context.stroke(circle(r * (0.25 + 0.75 * p)), with: .color(Party.blue.opacity((1 - p) * 0.8)), lineWidth: 1.5)
                }
            }
        }
        .frame(width: size, height: size)
    }
}

/// One-shot confetti. Fires whenever `trigger` changes.
struct ConfettiBurst: View {
    let trigger: Int
    @State var started: Date?

    private struct Piece {
        let angle: Double
        let speed: Double
        let spin: Double
        let width: Double
        let height: Double
        let color: Color
    }

    private static let pieces: [Piece] = {
        var state: UInt64 = 0x9E37_79B9_7F4A_7C15
        func next() -> Double { // small deterministic generator; confetti does not need more
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Double(state >> 11) / Double(1 << 53)
        }
        let colors: [Color] = [Party.pink, Party.blue, Party.lime, Party.sun, Party.violet, .white]
        return (0..<80).map { _ in
            Piece(
                angle: -Double.pi * (0.08 + 0.84 * next()), // upwards fan
                speed: 0.35 + 0.75 * next(), spin: next() * 2 - 1,
                width: 5 + 6 * next(), height: 8 + 8 * next(),
                color: colors[Int(next() * Double(colors.count)) % colors.count])
        }
    }()

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: started == nil)) { timeline in
            Canvas { context, size in
                guard let started else { return }
                let progress = timeline.date.timeIntervalSince(started) / 1.9
                guard progress >= 0, progress < 1 else { return }
                let origin = CGPoint(x: size.width / 2, y: size.height * 0.30)
                let ease = 1 - pow(1 - progress, 2.2) // fast launch, slow drift
                let alpha = (1 - progress).squareRoot()
                for piece in ConfettiBurst.pieces {
                    let distance = piece.speed * Double(size.width) * 0.8 * ease
                    let x = Double(origin.x) + cos(piece.angle) * distance
                    let y = Double(origin.y) + sin(piece.angle) * distance + Double(size.height) * 0.55 * progress * progress
                    var piecePath = Path(roundedRect: CGRect(x: -piece.width / 2, y: -piece.height / 2, width: piece.width, height: piece.height), cornerRadius: 2)
                    piecePath = piecePath.applying(
                        CGAffineTransform(translationX: x, y: y).rotated(by: piece.spin * progress * 15.7))
                    context.fill(piecePath, with: .color(piece.color.opacity(alpha)))
                }
            }
        }
        .allowsHitTesting(false)
        .onChange(of: trigger) { _ in
            started = Date()
            // Stop redrawing once the burst is over.
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                if let s = started, Date().timeIntervalSince(s) >= 1.9 { started = nil }
            }
        }
    }
}

// MARK: - Small pieces

/// The wordmark, painted with the brand gradient, over a small tagline.
struct Wordmark: View {
    var body: some View {
        VStack(spacing: 2) {
            Text("Hearby")
                .font(Party.font(34, .bold))
                .foregroundStyle(LinearGradient(colors: [Party.pink, Party.violet, Party.blue], startPoint: .leading, endPoint: .trailing))
            Text("ONE BEAT · EVERY PHONE")
                .font(Party.font(11, .medium))
                .tracking(2.4)
                .foregroundColor(Party.inkFaint)
        }
        .frame(maxWidth: .infinity)
    }
}

/// A colourful badge with the person's initial; the colours are picked from their name.
struct Avatar: View {
    let name: String
    var size: CGFloat = 44

    private static let palette: [(Color, Color)] = [
        (Party.pink, Party.sun), (Party.blue, Party.violet), (Party.lime, Party.blue),
        (Party.coral, Party.pink), (Party.violet, Party.pink), (Party.sun, Party.coral),
    ]

    var body: some View {
        // A stable hash: Swift's own hashValue changes on every launch.
        let hash = name.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0x7FFF_FFFF }
        let colors = Avatar.palette[hash % Avatar.palette.count]
        let initial = name.first(where: { $0.isLetter || $0.isNumber }).map { String($0).uppercased() } ?? "♪"
        Circle()
            .fill(LinearGradient(colors: [colors.0, colors.1], startPoint: .topLeading, endPoint: .bottomTrailing))
            .frame(width: size, height: size)
            .overlay(Text(initial).font(Party.font(18, .bold)).foregroundColor(Party.night))
    }
}

/// One number with its caption, used in the row of live stats.
struct Stat: View {
    let value: String
    let label: String
    var accent = Color.white

    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(Party.font(16, .semibold)).monospacedDigit().foregroundColor(accent).lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(Party.font(11)).foregroundColor(Party.inkSoft).lineLimit(1)
        }
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Party.glass))
    }
}

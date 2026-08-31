// The GPU op-chain executor (iOS), used by the UIKit host. Runs a Chuks
// effects.chain (JSON op list) on the GPU via Core Image (Metal-backed). Ops are generic
// primitives; filters are Chuks packages that compose them. See effects.chuks +
// docs/mobile-compute-and-effects.md. abi 1.
import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins

private let chuksEffectsCtx = CIContext(options: [.workingColorSpace: CGColorSpace(name: CGColorSpace.linearSRGB) as Any])

// Apply an op-chain (JSON) to an image, returning the filtered image. On any parse/exec
// failure the input is returned unchanged (a filter never blanks the picture).
func runOpChain(_ img: UIImage, _ json: String) -> UIImage {
    if json.isEmpty { return img }
    guard let data = json.data(using: .utf8),
          let ops = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]],
          var ci = CIImage(image: img) else { return img }
    let extent = ci.extent
    var pendingSource: CIImage? = nil   // a generated gradient/noise, consumed by the next blend

    for op in ops {
        guard let kind = op["op"] as? String else { continue }
        switch kind {
        case "colorMatrix":
            guard let m = floats(op["m"]), m.count >= 20 else { break }
            let f = CIFilter.colorMatrix()
            f.inputImage = ci
            f.rVector = CIVector(x: m[0], y: m[1], z: m[2], w: m[3])
            f.gVector = CIVector(x: m[5], y: m[6], z: m[7], w: m[8])
            f.bVector = CIVector(x: m[10], y: m[11], z: m[12], w: m[13])
            f.aVector = CIVector(x: m[15], y: m[16], z: m[17], w: m[18])
            f.biasVector = CIVector(x: m[4], y: m[9], z: m[14], w: m[19])
            ci = f.outputImage ?? ci

        case "curves":
            // CIToneCurve takes 5 points; sample the supplied control points to 5.
            guard let pts = points(op["pts"]) else { break }
            let p = sampleFive(pts)
            let f = CIFilter.toneCurve()
            f.inputImage = ci
            f.point0 = p[0]; f.point1 = p[1]; f.point2 = p[2]; f.point3 = p[3]; f.point4 = p[4]
            ci = f.outputImage ?? ci

        case "threshold":
            let levels = (op["levels"] as? NSNumber)?.floatValue ?? 6
            let f = CIFilter.colorPosterize()
            f.inputImage = ci; f.levels = max(2, levels)
            ci = f.outputImage ?? ci

        case "gradientMap":
            guard let grad = gradientImage(op["stops"], linear: true, geo: [0,50,100,50], extent: extent) else { break }
            let f = CIFilter.colorMap()
            f.inputImage = ci; f.gradientImage = grad
            ci = f.outputImage ?? ci

        case "convolve":
            guard let k = floats(op["k"]) else { break }
            let w = (op["w"] as? NSNumber)?.intValue ?? Int(Double(k.count).squareRoot().rounded())
            let sep = (op["sep"] as? NSNumber)?.boolValue ?? false
            ci = convolve(ci, k, w, sep) ?? ci

        case "gradient":
            let kind = (op["kind"] as? String) ?? "linear"
            let geo = ints(op["geo"]) ?? [0,0,100,100]
            pendingSource = gradientImage(op["stops"], linear: kind != "radial", geo: geo, extent: extent)

        case "noise":
            let scale = (op["scale"] as? NSNumber)?.doubleValue ?? 50
            pendingSource = noiseImage(scale: CGFloat(scale), extent: extent)

        case "blend":
            let src = (op["src"] as? String) ?? ""
            let mode = (op["mode"] as? String) ?? "normal"
            let opacity = (op["opacity"] as? NSNumber)?.doubleValue ?? 100
            var overlay: CIImage? = pendingSource
            if overlay == nil, !src.isEmpty { overlay = loadOverlay(src, extent: extent) }
            pendingSource = nil
            if let ov = fade(overlay, CGFloat(opacity)) { ci = blendMode(mode, ov, ci) ?? ci }

        case "lut3d":
            if let src = op["img"] as? String, let cube = loadCube(src) {
                let f = CIFilter.colorCube()
                f.inputImage = ci; f.cubeDimension = cube.dim; f.cubeData = cube.data
                ci = f.outputImage ?? ci
            }

        default: break
        }
    }

    guard let cg = chuksEffectsCtx.createCGImage(ci, from: extent) else { return img }
    return UIImage(cgImage: cg)
}

// ── helpers ───────────────────────────────────────────────────────────────────
private func floats(_ v: Any?) -> [CGFloat]? { (v as? [Any])?.compactMap { ($0 as? NSNumber).map { CGFloat($0.doubleValue) } } }
private func ints(_ v: Any?) -> [Int]? { (v as? [Any])?.compactMap { ($0 as? NSNumber)?.intValue } }
private func points(_ v: Any?) -> [CGPoint]? {
    (v as? [Any])?.compactMap { pt in
        guard let a = (pt as? [Any])?.compactMap({ ($0 as? NSNumber)?.doubleValue }), a.count >= 2 else { return nil }
        return CGPoint(x: a[0] / 255.0, y: a[1] / 255.0)
    }
}
private func sampleFive(_ pts: [CGPoint]) -> [CGPoint] {
    guard pts.count >= 2 else { return (0..<5).map { CGPoint(x: CGFloat($0)/4, y: CGFloat($0)/4) } }
    return (0..<5).map { i -> CGPoint in
        let x = CGFloat(i) / 4.0
        var y = pts.first!.y
        for j in 1..<pts.count {
            let x0 = pts[j-1].x, y0 = pts[j-1].y, x1 = pts[j].x, y1 = pts[j].y
            if x <= x1 { let t = x1 > x0 ? (x - x0)/(x1 - x0) : 0; y = y0 + (y1 - y0)*max(0, min(1, t)); break }
            y = y1
        }
        return CGPoint(x: x, y: y)
    }
}
private func convolve(_ ci: CIImage, _ k: [CGFloat], _ w: Int, _ sep: Bool) -> CIImage? {
    if sep {   // 1D kernel run as H then V (O(r))
        let h = CIFilter.convolution9Horizontal(); h.inputImage = ci; h.weights = vec(pad(k, 9))
        let v = CIFilter.convolution9Vertical(); v.inputImage = h.outputImage; v.weights = vec(pad(k, 9))
        return v.outputImage
    }
    switch w {
    case 3: let f = CIFilter.convolution3X3(); f.inputImage = ci; f.weights = vec(pad(k, 9)); return f.outputImage
    case 5: let f = CIFilter.convolution5X5(); f.inputImage = ci; f.weights = vec(pad(k, 25)); return f.outputImage
    case 7: let f = CIFilter.convolution7X7(); f.inputImage = ci; f.weights = vec(pad(k, 49)); return f.outputImage
    default: let f = CIFilter.convolution9Horizontal(); f.inputImage = ci; f.weights = vec(pad(k, 9)); return f.outputImage
    }
}
private func pad(_ k: [CGFloat], _ n: Int) -> [CGFloat] { k.count >= n ? Array(k.prefix(n)) : k + Array(repeating: 0, count: n - k.count) }
private func vec(_ k: [CGFloat]) -> CIVector { CIVector(values: k, count: k.count) }
private func fade(_ img: CIImage?, _ opacity: CGFloat) -> CIImage? {
    guard let img = img else { return nil }
    if opacity >= 100 { return img }
    let f = CIFilter.colorMatrix(); f.inputImage = img; f.aVector = CIVector(x: 0, y: 0, z: 0, w: opacity/100.0); return f.outputImage
}
private func blendMode(_ mode: String, _ over: CIImage, _ base: CIImage) -> CIImage? {
    let f: CIFilter & CICompositeOperation
    switch mode {
    case "multiply": f = CIFilter.multiplyBlendMode()
    case "screen": f = CIFilter.screenBlendMode()
    case "overlay": f = CIFilter.overlayBlendMode()
    case "softlight": f = CIFilter.softLightBlendMode()
    case "difference": f = CIFilter.differenceBlendMode()
    case "add": f = CIFilter.additionCompositing()
    default: f = CIFilter.sourceOverCompositing()
    }
    f.inputImage = over; f.backgroundImage = base
    return f.outputImage?.cropped(to: base.extent)
}
private func gradientImage(_ stops: Any?, linear: Bool, geo: [Int], extent: CGRect) -> CIImage? {
    let s = (stops as? [Any]) ?? []
    let c0 = s.count > 0 ? hexColorCI(stopColor(s[0])) : CIColor.black
    let c1 = s.count > 1 ? hexColorCI(stopColor(s[s.count-1])) : CIColor.white
    let g = geo.count >= 4 ? geo : [0,0,100,100]
    let p0 = CGPoint(x: CGFloat(g[0])/100*extent.width, y: CGFloat(g[1])/100*extent.height)
    let p1 = CGPoint(x: CGFloat(g[2])/100*extent.width, y: CGFloat(g[3])/100*extent.height)
    if linear {
        let f = CIFilter.linearGradient(); f.point0 = p0; f.point1 = p1; f.color0 = c0; f.color1 = c1
        return f.outputImage?.cropped(to: extent)
    }
    let f = CIFilter.radialGradient(); f.center = p0; f.radius0 = 0
    f.radius1 = Float(hypot(p1.x - p0.x, p1.y - p0.y)); f.color0 = c0; f.color1 = c1
    return f.outputImage?.cropped(to: extent)
}
private func noiseImage(scale: CGFloat, extent: CGRect) -> CIImage? {
    let n = CIFilter.randomGenerator().outputImage?.cropped(to: extent)
    let g = CIFilter.colorMatrix(); g.inputImage = n   // desaturate to monochrome grain
    g.rVector = CIVector(x: 0.33, y: 0.33, z: 0.33, w: 0); g.gVector = CIVector(x: 0.33, y: 0.33, z: 0.33, w: 0)
    g.bVector = CIVector(x: 0.33, y: 0.33, z: 0.33, w: 0); g.aVector = CIVector(x: 0, y: 0, z: 0, w: 0); g.biasVector = CIVector(x: 0, y: 0, z: 0, w: 1)
    return g.outputImage
}
private func stopColor(_ v: Any) -> String { ((v as? [Any])?.last as? String) ?? (v as? String) ?? "000000" }
private func hexColorCI(_ hex: String) -> CIColor {
    var s = hex; if s.hasPrefix("#") { s.removeFirst() }
    guard s.count == 6, let n = UInt32(s, radix: 16) else { return .black }
    return CIColor(red: CGFloat((n >> 16) & 0xff)/255, green: CGFloat((n >> 8) & 0xff)/255, blue: CGFloat(n & 0xff)/255)
}
private func loadOverlay(_ src: String, extent: CGRect) -> CIImage? {
    if let cached = ChuksImageLoader.shared.cached(src), let ci = CIImage(image: cached) { return ci.cropped(to: extent) }
    if let url = Bundle.main.url(forResource: src, withExtension: nil), let ui = UIImage(contentsOfFile: url.path), let ci = CIImage(image: ui) { return ci.cropped(to: extent) }
    return nil
}
private func loadCube(_ src: String) -> (dim: Float, data: Data)? { nil }   // TODO: Hald-image -> cube data

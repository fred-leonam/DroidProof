package io.github.fredleonam.droidproof.host

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

data class HierarchyMatchResult(
    val matched: Boolean,
    val nodeCount: Int,
    val detail: String,
)

class HierarchyValidationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class UiHierarchyParser(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
) {
    init {
        require(maxBytes in 1 until Int.MAX_VALUE.toLong()) { "Hierarchy byte limit is outside supported bounds." }
        require(maxNodes > 0) { "Hierarchy node limit must be positive." }
    }

    fun inspect(
        path: Path,
        expectedPackage: String,
        expectedResourceId: String,
        expectedText: String,
    ): HierarchyMatchResult {
        return inspect(expectedPackage, expectedResourceId, expectedText, null, path)
    }

    fun inspectComposeSemantics(
        path: Path,
        expectedPackage: String,
        expectedResourceId: String,
        expectedText: String,
        expectedContentDescription: String,
    ): HierarchyMatchResult = inspect(expectedPackage, expectedResourceId, expectedText, expectedContentDescription, path)

    private fun inspect(
        expectedPackage: String,
        expectedResourceId: String,
        expectedText: String,
        expectedContentDescription: String?,
        path: Path,
    ): HierarchyMatchResult {
        val handler = MatchingNodeHandler(expectedPackage, expectedResourceId, expectedText, expectedContentDescription, maxNodes)
        parse(path, handler)
        val detail =
            if (handler.matched) {
                if (expectedContentDescription == null) {
                    "One accessibility node matched package, resource ID and exact text."
                } else {
                    "One Compose semantics node matched package, test-tag resource ID, exact text and content description."
                }
            } else {
                "No single accessibility node matched all expected attributes."
            }
        return HierarchyMatchResult(handler.matched, handler.nodeCount, detail)
    }

    fun resolveTap(
        path: Path,
        expectedPackage: String,
        resourceId: String,
    ): TapCoordinates = inspectTap(path, expectedPackage, resourceId).coordinatesOrThrow()

    fun inspectTap(
        path: Path,
        expectedPackage: String,
        resourceId: String,
    ): TapResolution {
        if (!resourceId.startsWith("$expectedPackage:id/")) {
            throw HierarchyValidationException("Tap resource ID does not belong to the expected package.")
        }
        val handler = MatchingNodeHandler(expectedPackage, resourceId, null, null, maxNodes)
        parse(path, handler)
        return try {
            TapResolution(coordinates(handler))
        } catch (error: HierarchyValidationException) {
            TapResolution(detail = error.message)
        }
    }

    private fun coordinates(handler: MatchingNodeHandler): TapCoordinates {
        if (handler.matchCount != 1) throw HierarchyValidationException("Tap requires exactly one package/resource ID target.")
        val bounds = handler.bounds ?: throw HierarchyValidationException("Tap target bounds are missing.")
        val match = BOUNDS.matchEntire(bounds) ?: throw HierarchyValidationException("Tap target bounds are malformed.")
        val values =
            match.groupValues.drop(1).map {
                it.toIntOrNull() ?: throw HierarchyValidationException("Tap target coordinates are outside supported bounds.")
            }
        val (left, top, right, bottom) = values
        if (right <= left || bottom <= top) throw HierarchyValidationException("Tap target bounds must have positive area.")
        return TapCoordinates(left + (right - left) / 2, top + (bottom - top) / 2)
    }

    private fun parse(
        path: Path,
        handler: MatchingNodeHandler,
    ) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw HierarchyValidationException("UI hierarchy must be a regular non-symbolic-link file.")
        }
        if (Files.size(path) !in 1..maxBytes) throw HierarchyValidationException("UI hierarchy exceeds the accepted byte bounds.")
        try {
            val reader = secureFactory().newSAXParser().xmlReader
            reader.entityResolver = EntityResolver { _, _ -> throw SAXException("External entities are disabled.") }
            reader.contentHandler = handler
            reader.errorHandler = handler
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val bytes = input.readNBytes((maxBytes + 1).toInt())
                if (bytes.size > maxBytes) throw SAXException("UI hierarchy byte limit exceeded.")
                reader.parse(InputSource(bytes.inputStream()))
            }
        } catch (error: Exception) {
            throw HierarchyValidationException("UI hierarchy XML is malformed or unsafe.", error)
        }
    }

    private fun secureFactory(): SAXParserFactory =
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
}

private class MatchingNodeHandler(
    private val expectedPackage: String,
    private val expectedResourceId: String,
    private val expectedText: String?,
    private val expectedContentDescription: String?,
    private val maxNodes: Int,
) : DefaultHandler() {
    val matched: Boolean get() = matchCount > 0
    var matchCount = 0
        private set
    var bounds: String? = null
        private set
    var nodeCount = 0
        private set

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        if (qName != "node" && localName != "node") return
        nodeCount++
        if (nodeCount > maxNodes) throw SAXException("UI hierarchy node limit exceeded.")
        if (attributes.getValue("package") == expectedPackage &&
            attributes.getValue("resource-id") == expectedResourceId &&
            (expectedText == null || attributes.getValue("text") == expectedText) &&
            (expectedContentDescription == null || attributes.getValue("content-desc") == expectedContentDescription)
        ) {
            matchCount++
            bounds = attributes.getValue("bounds")
        }
    }
}

private const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L
private const val DEFAULT_MAX_NODES = 20_000

data class TapCoordinates(val x: Int, val y: Int) {
    init {
        require(x >= 0 && y >= 0) { "Tap coordinates must be nonnegative." }
    }
}

private val BOUNDS = Regex("""\[([0-9]+),([0-9]+)]\[([0-9]+),([0-9]+)]""")

data class TapResolution(val coordinates: TapCoordinates? = null, val detail: String? = null) {
    fun coordinatesOrThrow(): TapCoordinates =
        coordinates ?: throw HierarchyValidationException(detail ?: "Tap target could not be resolved.")
}

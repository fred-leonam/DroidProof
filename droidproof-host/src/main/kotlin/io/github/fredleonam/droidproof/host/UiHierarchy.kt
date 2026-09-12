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
    fun inspect(
        path: Path,
        expectedPackage: String,
        expectedResourceId: String,
        expectedText: String,
    ): HierarchyMatchResult {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw HierarchyValidationException("UI hierarchy must be a regular non-symbolic-link file.")
        }
        val size = Files.size(path)
        if (size !in 1..maxBytes) throw HierarchyValidationException("UI hierarchy exceeds the accepted byte bounds.")

        val handler = MatchingNodeHandler(expectedPackage, expectedResourceId, expectedText, maxNodes)
        try {
            val factory = secureFactory()
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = EntityResolver { _, _ -> throw SAXException("External entities are disabled.") }
            reader.contentHandler = handler
            Files.newInputStream(path).use { reader.parse(InputSource(it)) }
        } catch (error: Exception) {
            throw HierarchyValidationException("UI hierarchy XML is malformed or unsafe.", error)
        }
        val detail =
            if (handler.matched) {
                "One accessibility node matched package, resource ID and exact text."
            } else {
                "No single accessibility node matched all three expected attributes."
            }
        return HierarchyMatchResult(handler.matched, handler.nodeCount, detail)
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
    private val expectedText: String,
    private val maxNodes: Int,
) : DefaultHandler() {
    var matched = false
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
            attributes.getValue("text") == expectedText
        ) {
            matched = true
        }
    }
}

private const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L
private const val DEFAULT_MAX_NODES = 20_000

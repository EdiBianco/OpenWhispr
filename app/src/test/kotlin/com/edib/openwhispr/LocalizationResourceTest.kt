package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourceTest {
    @Test fun `English and French string resources have matching keys and format arguments`() {
        val english = readValues("values/strings.xml", "string")
        val french = readValues("values-fr/strings.xml", "string")
        assertEquals("English and French string keys differ", english.keys, french.keys)
        english.forEach { (name, value) ->
            assertEquals("Format arguments differ for $name", formatArguments(value), formatArguments(french.getValue(name)))
        }
    }

    @Test fun `English and French plurals have matching quantities and format arguments`() {
        val english = readPlurals("values/plurals.xml")
        val french = readPlurals("values-fr/plurals.xml")
        assertEquals("English and French plural keys differ", english.keys, french.keys)
        english.forEach { (name, quantities) ->
            val translated = french.getValue(name)
            assertEquals("Plural quantities differ for $name", quantities.keys, translated.keys)
            quantities.forEach { (quantity, value) ->
                assertEquals(
                    "Format arguments differ for $name/$quantity",
                    formatArguments(value),
                    formatArguments(translated.getValue(quantity))
                )
            }
        }
    }

    private fun readValues(relativePath: String, tagName: String): Map<String, String> {
        val elements = parse(relativePath).getElementsByTagName(tagName)
        val values = linkedMapOf<String, String>()
        for (index in 0 until elements.length) {
            val element = elements.item(index) as Element
            val name = element.getAttribute("name")
            require(name !in values) { "Duplicate $tagName resource: $name ($relativePath)" }
            values[name] = element.textContent
        }
        return values
    }

    private fun readPlurals(relativePath: String): Map<String, Map<String, String>> {
        val elements = parse(relativePath).getElementsByTagName("plurals")
        val plurals = linkedMapOf<String, Map<String, String>>()
        for (index in 0 until elements.length) {
            val element = elements.item(index) as Element
            val name = element.getAttribute("name")
            require(name !in plurals) { "Duplicate plural resource: $name ($relativePath)" }
            val quantities = linkedMapOf<String, String>()
            val items = element.getElementsByTagName("item")
            for (itemIndex in 0 until items.length) {
                val item = items.item(itemIndex) as Element
                val quantity = item.getAttribute("quantity")
                require(quantity !in quantities) { "Duplicate quantity $name/$quantity ($relativePath)" }
                quantities[quantity] = item.textContent
            }
            plurals[name] = quantities
        }
        return plurals
    }

    private fun parse(relativePath: String) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(File("src/main/res", relativePath))

    private fun formatArguments(value: String): List<String> =
        Regex("%(?:[1-9][0-9]*\\$)?[a-zA-Z]").findAll(value).map { it.value }.sorted().toList()
}

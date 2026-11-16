package dev.fancycrests.core.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.StringReader
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag

/** YAML input mapped to the existing strict, path-aware configuration validator. */
internal object ConfigYaml {
    fun parse(input: String): JsonElement {
        val options = LoaderOptions().apply {
            maxAliasesForCollections = 50
            codePointLimit = 3_000_000
            nestingDepthLimit = 100
        }
        val root = Yaml(options).compose(StringReader(input)) ?: return JsonNull.INSTANCE
        val ancestors = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
        return toJson(root, ancestors, 0)
    }

    private fun toJson(node: Node, ancestors: MutableSet<Node>, depth: Int): JsonElement {
        require(depth <= 100 && ancestors.add(node)) { "recursive or excessively nested YAML" }
        try {
            return when (node) {
                is MappingNode -> JsonObject().apply {
                    for (entry in node.value) {
                        val key = (entry.keyNode as? ScalarNode)?.value
                            ?: throw IllegalArgumentException("mapping keys must be strings")
                        require(!has(key)) { "duplicate mapping key '$key'" }
                        add(key, toJson(entry.valueNode, ancestors, depth + 1))
                    }
                }
                is SequenceNode -> JsonArray().apply {
                    node.value.forEach { add(toJson(it, ancestors, depth + 1)) }
                }
                is ScalarNode -> when (node.tag) {
                    Tag.NULL -> JsonNull.INSTANCE
                    Tag.STR -> JsonPrimitive(node.value)
                    Tag.BOOL -> JsonPrimitive(node.value.equals("true", ignoreCase = true) ||
                        node.value.equals("yes", ignoreCase = true) || node.value.equals("on", ignoreCase = true))
                    Tag.INT, Tag.FLOAT -> {
                        require(NUMBER.matches(node.value)) { "unsupported number '${node.value}'" }
                        JsonParser.parseString(node.value)
                    }
                    else -> throw IllegalArgumentException("unsupported YAML tag ${node.tag}")
                }
                else -> throw IllegalArgumentException("unsupported YAML node")
            }
        } finally {
            ancestors.remove(node)
        }
    }

    fun dump(root: JsonElement): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            isPrettyFlow = true
            width = 120
        }
        return Yaml(options).dump(toValues(root))
    }

    private fun toValues(element: JsonElement): Any? = when {
        element.isJsonNull -> null
        element.isJsonObject -> LinkedHashMap<String, Any?>().apply {
            element.asJsonObject.entrySet().forEach { (key, value) -> put(key, toValues(value)) }
        }
        element.isJsonArray -> element.asJsonArray.map { toValues(it) }
        element.asJsonPrimitive.isBoolean -> element.asBoolean
        element.asJsonPrimitive.isString -> element.asString
        INTEGER.matches(element.asString) -> BigInteger(element.asString)
        else -> BigDecimal(element.asString)
    }

    private val INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
}

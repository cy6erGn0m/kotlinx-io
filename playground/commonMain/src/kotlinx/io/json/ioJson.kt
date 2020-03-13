/*
 * Copyright 2017-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
package kotlinx.io.json

import kotlinx.io.*
import kotlinx.io.bytes.*
import kotlinx.io.json.internal.*
import kotlinx.io.text.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
import kotlin.jvm.*
import kotlin.native.concurrent.*
import kotlin.reflect.*

/**
 * The main entry point to work with JSON serialization.
 * It is typically used by constructing an application-specific instance, with configured json-specific behaviour
 * ([configuration] constructor parameter) and, if necessary, registered
 * custom serializers (in [SerialModule] provided by [context] constructor parameter).
 * Then constructed instance can be used either as regular [SerialFormat] or [StringFormat]
 * or for converting objects to [JsonElement] back and forth.
 *
 * This is the only serial format which has first-class [JsonElement] support.
 * Any serializable class can be serialized to or from [JsonElement] with [Json.fromJson] and [Json.toJson] respectively or
 * serialize properties of [JsonElement] type.
 *
 * Example of usage:
 * ```
 * @Serializable
 * class DataHolder(val id: Int, val data: String, val extensions: JsonElement)
 *
 * val json = Json(JsonConfiguration.Default)
 * val instance = DataHolder(42, "some data", json { "additional key" to "value" })
 *
 * // Plain StringFormat usage
 * val stringOutput: String = json.stringify(instance)
 *
 * // JsonElement serialization specific for Json only
 * val jsonTree: JsonElement = json.toJson(instance)
 *
 * // Deserialize from string
 * val deserialized: DataHolder = json.parse<DataHolder>(stringOutput)
 *
 * // Deserialize from json tree, Json-specific
 * val deserializedFromTree: DataHolder = json.fromJson<DataHolder>(jsonTree)
 *
 *  // Deserialize from string to json tree, Json-specific
 *  val deserializedToTree: JsonElement = json.fromJson<JsonElement>(stringOutput)
 * ```
 *
 * Note that `@ImplicitReflectionSerializer` are used in order to omit `DataHolder.serializer`, but this is a temporary limitation.
 */
public class ioJson

/**
 * Default Json constructor not marked as unstable API.
 * To configure Json format behavior while still using only stable API it is possible to use `JsonConfiguration.copy` factory:
 * ```
 * val json = Json(configuration: = JsonConfiguration.Stable.copy(strictMode = false))
 * ```
 */
public constructor(
    @JvmField internal val configuration: ioJsonConfiguration = ioJsonConfiguration.Stable,
    context: SerialModule = EmptyModule
) : AbstractSerialFormat(context + defaultJsonModule), StringFormat {

    /**
     * DSL-like constructor for Json.
     * This constructor is marked with unstable default: its default parameters values and behaviour may change in the next releases.
     */
    @UnstableDefault
    public constructor(block: ioJsonBuilder.() -> Unit) : this(
        ioJsonBuilder().apply { block() })

    @OptIn(UnstableDefault::class)
    @Deprecated(
        message = "Default constructor is deprecated, please specify desired configuration explicitly or use Json(JsonConfiguration.Default)",
        replaceWith = ReplaceWith("Json(JsonConfiguration.Default)"),
        level = DeprecationLevel.ERROR
    )
    public constructor() : this(ioJsonConfiguration(useArrayPolymorphism = true))

    @OptIn(UnstableDefault::class)
    private constructor(builder: ioJsonBuilder) : this(builder.buildConfiguration(), builder.buildModule())

    /**
     * Serializes [obj] into an equivalent JSON using provided [serializer].
     * @throws [JsonException] if given value can not be encoded
     * @throws [SerializationException] if given value can not be serialized
     */
    public override fun <T> stringify(serializer: SerializationStrategy<T>, obj: T): String {
        val result = BytesOutput()
        val encoder = ioStreamingJsonOutput(
            result, this,
            ioWriteMode.OBJ,
            arrayOfNulls(ioWriteMode.values().size)
        )
        encoder.encode(serializer, obj)
        return result.createInput().readUtf8String()
    }

    public fun <T> stringify(serializer: SerializationStrategy<T>, obj: T, output: Output) {
        val encoder = ioStreamingJsonOutput(
            output, this,
            ioWriteMode.OBJ,
            arrayOfNulls(ioWriteMode.values().size)
        )
        encoder.encode(serializer, obj)
    }

    /**
     * Serializes [value] into an equivalent [JsonElement] using provided [serializer].
     * @throws [JsonException] if given value can not be encoded
     * @throws [SerializationException] if given value can not be serialized
     */
    public fun <T> toJson(serializer: SerializationStrategy<T>, value: T): JsonElement {
        return writeJson(value, serializer)
    }

    /**
     * Serializes [value] into an equivalent [JsonElement] using serializer registered in the module.
     * @throws [JsonException] if given value can not be encoded
     * @throws [SerializationException] if given value can not be serialized
     */
    @ImplicitReflectionSerializer
    public inline fun <reified T : Any> toJson(value: T): JsonElement {
        return toJson(context.getContextualOrDefault(T::class), value)
    }

    /**
     * Deserializes given json [string] into a corresponding object of type [T] using provided [deserializer].
     * @throws [JsonException] in case of malformed json
     * @throws [SerializationException] if given input can not be deserialized
     */
    public override fun <T> parse(deserializer: DeserializationStrategy<T>, string: String): T {
        val reader = ioJsonReader(buildInput { writeUtf8String(string) })
        val input = ioStreamingJsonInput(this, ioWriteMode.OBJ, reader)
        val result = input.decode(deserializer)
        if (!reader.isDone) {
            error("Reader has not consumed the whole input: $reader")
        }
        return result
    }

    public fun <T> parse(deserializer: DeserializationStrategy<T>, input: Input): T {
        val reader = ioJsonReader(input)
        val jsonInput = ioStreamingJsonInput(this, ioWriteMode.OBJ, reader)
        val result = jsonInput.decode(deserializer)
        if (!reader.isDone) {
            error("Reader has not consumed the whole input: $reader")
        }
        return result
    }

    /**
     * Deserializes given json [string] into a corresponding [JsonElement] representation.
     * @throws [JsonException] in case of malformed json
     * @throws [SerializationException] if given input can not be deserialized
     */
    public fun parseJson(string: String): JsonElement {
        return parse(ioJsonElementSerializer, string)
    }

    /**
     * Deserializes [json] element into a corresponding object of type [T] using provided [deserializer].
     * @throws [JsonException] in case of malformed json
     * @throws [SerializationException] if given input can not be deserialized
     */
    public fun <T> fromJson(deserializer: DeserializationStrategy<T>, json: JsonElement): T {
        return readJson(json, deserializer)
    }

    /**
     * Deserializes [json] element into a corresponding object of type [T] using serializer registered in the module.
     * @throws [JsonException] in case of malformed json
     * @throws [SerializationException] if given input can not be deserialized
     */
    @ImplicitReflectionSerializer
    public inline fun <reified T : Any> fromJson(tree: JsonElement): T =
        fromJson(context.getContextualOrDefault(T::class), tree)

    companion object : StringFormat {
        @Suppress("DEPRECATION")
        @UnstableDefault
        public val plain = ioJson(ioJsonConfiguration(useArrayPolymorphism = true))

        @UnstableDefault
        public val unquoted = ioJson(
            ioJsonConfiguration(unquoted = true, useArrayPolymorphism = true)
        )

        @UnstableDefault
        public val indented = ioJson(
            ioJsonConfiguration(prettyPrint = true, useArrayPolymorphism = true)
        )

        @UnstableDefault
        public val nonstrict = ioJson(
            ioJsonConfiguration(strictMode = false, useArrayPolymorphism = true)
        )

        @OptIn(UnstableDefault::class)
        override val context: SerialModule
            get() = plain.context

        @UnstableDefault
        override fun <T> stringify(serializer: SerializationStrategy<T>, obj: T): String =
            plain.stringify(serializer, obj)

        @UnstableDefault
        fun <T> stringify(serializer: SerializationStrategy<T>, obj: T, output: Output) {
            plain.stringify(serializer, obj, output)
        }


        @UnstableDefault
        override fun <T> parse(deserializer: DeserializationStrategy<T>, string: String): T =
            plain.parse(deserializer, string)

        @UnstableDefault
        fun <T> parse(deserializer: DeserializationStrategy<T>, input: Input): T =
            plain.parse(deserializer, input)
    }
}

/**
 * Builder to conveniently build Json instances.
 * Properties of this builder are directly matched with properties of [ioJsonConfiguration].
 */
@UnstableDefault
public class ioJsonBuilder {
    public var encodeDefaults: Boolean = true
    public var strictMode: Boolean = true
    public var unquoted: Boolean = false
    public var allowStructuredMapKeys: Boolean = false
    public var prettyPrint: Boolean = false
    public var indent: String = "    "
    public var useArrayPolymorphism: Boolean = false
    public var classDiscriminator: String = "type"
    public var serialModule: SerialModule = EmptyModule

    public fun buildConfiguration(): ioJsonConfiguration =
        ioJsonConfiguration(
            encodeDefaults,
            strictMode,
            unquoted,
            allowStructuredMapKeys,
            prettyPrint,
            indent,
            useArrayPolymorphism,
            classDiscriminator
        )

    public fun buildModule(): SerialModule = serialModule
}

@SharedImmutable
private val defaultJsonModule = serializersModuleOf(
    mapOf<KClass<*>, KSerializer<*>>(
        JsonElement::class to ioJsonElementSerializer,
        JsonPrimitive::class to ioJsonPrimitiveSerializer,
        JsonLiteral::class to ioJsonLiteralSerializer,
        JsonNull::class to ioJsonNullSerializer,
        JsonObject::class to ioJsonObjectSerializer,
        JsonArray::class to ioJsonArraySerializer
    )
)

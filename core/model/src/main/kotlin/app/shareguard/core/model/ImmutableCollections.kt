package app.shareguard.core.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A serializable immutable list used at model boundaries.
 *
 * Kotlin's [List] interface is read-only rather than immutable. This wrapper snapshots every incoming
 * iterable into a [PersistentList], so a caller cannot mutate an already-created model through an alias.
 */
@Serializable(with = ImmutableListSerializer::class)
class ImmutableList<T> private constructor(
    private val values: PersistentList<T>,
) : List<T> by values {

    fun add(element: T): ImmutableList<T> = ImmutableList(values.add(element))

    fun addAll(elements: Iterable<T>): ImmutableList<T> =
        ImmutableList(values.addAll(elements.toList()))

    fun remove(element: T): ImmutableList<T> = ImmutableList(values.remove(element))

    fun set(index: Int, element: T): ImmutableList<T> = ImmutableList(values.set(index, element))

    fun toPersistentList(): PersistentList<T> = values

    override fun equals(other: Any?): Boolean = when (other) {
        is ImmutableList<*> -> values == other.values
        is List<*> -> values == other
        else -> false
    }

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.toString()

    companion object {
        fun <T> empty(): ImmutableList<T> = ImmutableList(persistentListOf())

        fun <T> of(vararg elements: T): ImmutableList<T> =
            ImmutableList(elements.toList().toPersistentList())

        fun <T> copyOf(elements: Iterable<T>): ImmutableList<T> = when (elements) {
            is ImmutableList<T> -> elements
            else -> ImmutableList(elements.toPersistentList())
        }
    }
}

class ImmutableListSerializer<T>(elementSerializer: KSerializer<T>) : KSerializer<ImmutableList<T>> {
    private val delegate = ListSerializer(elementSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ImmutableList<T>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): ImmutableList<T> =
        ImmutableList.copyOf(delegate.deserialize(decoder))
}

fun <T> immutableListOf(vararg elements: T): ImmutableList<T> = ImmutableList.of(*elements)

fun <T> Iterable<T>.toImmutableList(): ImmutableList<T> = ImmutableList.copyOf(this)

package com.github.weisj.darkmode.platform.settings

import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty0

interface SettingsContainerProvider {
    val enabled : Boolean
    fun create() : SettingsContainer
}

open class SingletonSettingsContainerProvider(
    provider : () -> SettingsContainer,
    override val enabled : Boolean = true
) : SettingsContainerProvider {
    private val container by lazy(provider)
    override fun create(): SettingsContainer = container
}

interface SettingsContainer : SettingsGroup {
    val unnamedGroup: SettingsGroup
    val hiddenGroup: SettingsGroup

    fun onSettingsLoaded()

    override fun allProperties(): List<ValueProperty<Any>> =
        subgroups.map { it.allProperties() }.flatten() + unnamedGroup + hiddenGroup
}

fun SettingsContainer.init() {
    allProperties().forEach { it.activeCondition.build() }
}

fun SettingsContainer.getWithName(name: String): Lazy<ValueProperty<Any>> =
    allProperties().findWithName(name).assertNonNull("Property with name '$name' not found.")

fun <T> SettingsContainer.getWithProperty(prop: KMutableProperty0<T>): Lazy<ValueProperty<T>> =
    allProperties().findWithProperty(prop).assertNonNull("Property with name '${prop.name}' not found.")

fun SettingsGroup.getWithName(name: String): Lazy<ValueProperty<Any>> =
    lazy {
        allProperties().findWithName(name).value ?: parent?.getWithName(name)?.value
    }.assertNonNull("Property with name '$name' not found.")

fun <T> SettingsGroup.getWithProperty(prop: KMutableProperty0<T>): Lazy<ValueProperty<T>> =
    lazy {
        allProperties().findWithProperty(prop).value ?: parent?.getWithProperty(prop)?.value
    }.assertNonNull("Property with name '${prop.name}' not found.")

fun SettingsContainer.getGroup(name: String): Lazy<SettingsGroup> =
    lazy { subgroups.find { it.name == name } }.assertNonNull("Group with name '$name' not found.")

fun Lazy<SettingsGroup>.getWithName(name: String): Lazy<ValueProperty<Any>> =
    lazy {
        findWithName(name).value ?: value.parent?.findWithName(name)?.value
    }.assertNonNull("Property with name '$name' not found.")

fun <T> Lazy<SettingsGroup>.getWithProperty(prop: KMutableProperty0<T>): Lazy<ValueProperty<T>> =
    lazy {
        findWithProperty(prop).value ?: value.parent?.findWithProperty(prop)?.value
    }.assertNonNull("Property with property '${prop.name}' not found.")


private fun Collection<ValueProperty<Any>>.findWithName(name: String): Lazy<ValueProperty<Any>?> =
    lazy { find { it.name == name } ?: throw IllegalStateException(name) }

private fun Lazy<Collection<ValueProperty<Any>>>.findWithName(name: String): Lazy<ValueProperty<Any>?> =
    lazy { value.findWithName(name).value }

private fun <T> Collection<ValueProperty<Any>>.findWithProperty(prop: KMutableProperty0<T>): Lazy<ValueProperty<T>?> =
    lazy { find { it.name == prop.name }?.castSafelyTo<ValueProperty<T>>() }

private fun <T> Lazy<Collection<ValueProperty<Any>>>.findWithProperty(prop: KMutableProperty0<T>): Lazy<ValueProperty<T>?> =
    lazy { value.findWithProperty(prop).value }

/**
 * Container for {@link ValueProperty}s. Properties can be group into
 * logical units using a {@SettingsGroup}.
 *
 * All properties not contained inside a {@SettingsGroup} will automatically belong
 * to the unnamed group of the container.
 */
abstract class DefaultSettingsContainer private constructor(
    override val unnamedGroup: SettingsGroup,
    override val hiddenGroup: SettingsGroup
) : SettingsContainer, SettingsGroup by unnamedGroup {
    constructor(identifier: String = "") : this(
        DefaultSettingsGroup(identifier = identifier),
        DefaultSettingsGroup(identifier = "${identifier}_hidden")
    )

    override val subgroups: MutableList<NamedSettingsGroup> = mutableListOf()

    override fun onSettingsLoaded() {}
    override fun allProperties(): List<ValueProperty<Any>> {
        return super<SettingsContainer>.allProperties()
    }
}

fun <T> SettingsGroup.add(property: ValueProperty<T>) {
    this.add(property.castSafelyTo()!!)
}

/**
 * Provides grouping for {@link ValueProperty}s
 */
interface SettingsGroup : MutableList<ValueProperty<Any>> {
    val identifier: String
    val parent: SettingsGroup?
    val subgroups: MutableList<NamedSettingsGroup>

    fun allProperties(): List<ValueProperty<Any>> = this + subgroups.map { it.allProperties() }.flatten()

    fun getIdentifierPath(): String = "${parent?.let { it.getIdentifierPath() + ":" } ?: ""}$identifier"
}

interface NamedSettingsGroup : SettingsGroup {
    val name: String
}

open class DefaultSettingsGroup internal constructor(
    override val identifier: String,
    override val parent: SettingsGroup?,
    private val properties: MutableList<ValueProperty<Any>>
) : SettingsGroup, MutableList<ValueProperty<Any>> by properties {
    override val subgroups: MutableList<NamedSettingsGroup> = mutableListOf()

    constructor(parent: SettingsGroup? = null, identifier: String) : this(
        identifier,
        parent,
        mutableListOf()
    )
}

class DefaultNamedSettingsGroup internal constructor(
    override val parent: SettingsGroup?,
    override val name: String,
    identifier: String? = null
) : DefaultSettingsGroup(parent, identifier ?: name), NamedSettingsGroup

/**
 * Wrapper for properties that provides a description and parser/writer used
 * for persistent storage.
 */
interface ValueProperty<T> : Observable<ValueProperty<T>> {
    val description: String
    val name: String
    var value: T
    var preview: T
    val group: SettingsGroup
    var activeCondition: Condition
}

fun <T> ValueProperty<T>.activeIf(condition: Condition) {
    activeCondition = condition
}

/**
 * Property with a backing value that has a different type than the exposed value.
 */
interface TransformingValueProperty<R, T> : ValueProperty<T> {
    val backingProperty: ValueProperty<R>
}

/**
 * Property that can be stored in String format.
 */
interface PersistentValueProperty<T> : TransformingValueProperty<T, String>

fun ValueProperty<*>.toTransformer(): TransformingValueProperty<Any, Any>? =
    castSafelyTo<TransformingValueProperty<Any, Any>>()

inline fun <reified T : Any> ValueProperty<T>.asPersistent(): PersistentValueProperty<T>? =
    castSafelyTo<PersistentValueProperty<T>>()

/**
 * The effective value of the property. If the property is a transforming property the
 * backing field is chosen. Because of this for a reference to a simple ValueProperty<T>
 * the most general value that can be returned is Any.
 */
val <T : Any> ValueProperty<T>.effectiveProperty : KMutableProperty0<Any>
    get() = effective<Any>()::value

inline fun <reified K : Any> ValueProperty<*>.effective(): ValueProperty<K> = effective(K::class)

fun <K : Any> ValueProperty<*>.effective(type: KClass<K>): ValueProperty<K> {
    var prop: ValueProperty<*> = this
    var trans: TransformingValueProperty<Any, Any>? = prop.toTransformer()
    while (trans != null) {
        prop = trans.backingProperty
        trans = prop.toTransformer()
    }
    if (!type.isInstance(prop.value)) throw IllegalArgumentException("Value ${prop.value} isn't of type $type.")
    if (!type.isInstance(prop.preview)) throw IllegalArgumentException("Preview ${prop.value} isn't of type $type.")
    return prop.castSafelyTo()!!
}

class SimpleValueProperty<T : Any> internal constructor(
    name: String?,
    description: String?,
    property: KMutableProperty0<T>,
    override val group: SettingsGroup
) : ValueProperty<T>, Observable<ValueProperty<T>> by DefaultObservable() {
    override val description: String = description ?: property.name
    override val name: String = name ?: property.name
    override var value: T by observable(property)
    override var preview: T by observable(value)
    override var activeCondition: Condition = conditionOf(true)

    init {
        registerListener(ValueProperty<T>::value) { _, new -> preview = new }
    }
}

open class SimpleTransformingValueProperty<R : Any, T : Any> internal constructor(
    final override val backingProperty: ValueProperty<R>,
    transformer: Transformer<R, T>
) : TransformingValueProperty<R, T>,
    Observable<ValueProperty<T>> by DefaultObservable() {
    override val description by backingProperty::description
    override val name by backingProperty::name
    override var activeCondition by backingProperty::activeCondition
    override val group by backingProperty::group

    final override var value: T by transformer.delegate(backingProp = backingProperty::value)
    override var preview: T by observable(value)
}

class SimplePersistentValueProperty<R : Any>(
    delegate: ValueProperty<R>,
    transformer: Transformer<R, String>
) : SimpleTransformingValueProperty<R, String>(delegate, transformer),
    PersistentValueProperty<R>

/**
 * Property that has a limited set of values the property can take on.
 */
abstract class ChoiceProperty<R, T> internal constructor(
    delegateProperty: TransformingValueProperty<R, T>
) : TransformingValueProperty<R, T> by delegateProperty {
    var choiceValue: R by delegateProperty.backingProperty::value
    var choices: List<R> = ArrayList()
    var renderer: (R) -> String = { it.toString() }
}

class TransformingChoiceProperty<R : Any, T : Any> internal constructor(
    property: TransformingValueProperty<R, T>
) : ChoiceProperty<R, T>(property) {
    constructor(property: ValueProperty<R>, transformer: Transformer<R, T>)
            : this(SimpleTransformingValueProperty(property, transformer))
}

class PersistentChoiceProperty<R : Any>(
    property: PersistentValueProperty<R>
) : ChoiceProperty<R, String>(property),
    PersistentValueProperty<R> {
    constructor(property: ValueProperty<R>, transformer: Transformer<R, String>)
            : this(SimplePersistentValueProperty(property, transformer))
}

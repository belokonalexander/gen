package com.belax.compiler

import com.belax.annotation.Config
import com.belax.annotation.VMState
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import me.eugeniomarletti.kotlin.metadata.KotlinClassMetadata
import me.eugeniomarletti.kotlin.metadata.isPrimary
import me.eugeniomarletti.kotlin.metadata.jvm.internalName
import me.eugeniomarletti.kotlin.metadata.kotlinMetadata
import me.eugeniomarletti.kotlin.metadata.proto
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.deserialization.NameResolver
import me.eugeniomarletti.kotlin.metadata.shadow.name.Name
import me.eugeniomarletti.kotlin.metadata.shadow.serialization.deserialization.getName
import java.io.File
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

@AutoService(Processor::class)
class Generator : AbstractProcessor() {

    private lateinit var messager: Messager
    private lateinit var filer: Filer

    override fun init(p0: ProcessingEnvironment) {
        super.init(p0)
        messager = p0.messager
        filer = p0.filer
    }

    private fun ClassName.setNullable(value: Boolean) = if (value) asNullable() else asNonNullable()
    private fun TypeName.setNullable(value: Boolean) = if (value) asNullable() else asNonNullable()

    private fun toClassName(name: Name, isNullable: Boolean): ClassName {
        return ClassName.bestGuess(name.toString().replace('/', '.')).setNullable(isNullable)
    }

    private fun getProperty(type: ProtoBuf.Type, nameResolver: NameResolver): TypeName {
        val name = nameResolver.getName(type.className)
        val className = toClassName(name, type.nullable)
        val arguments = mutableListOf<TypeName>()
        type.argumentList.forEach {
            arguments.add(getProperty(it.type, nameResolver))
        }
        return if (arguments.size > 0) {
            className.parameterizedBy(*arguments.toTypedArray()).setNullable(className.nullable)
        } else className
    }

    class Property(
            val name: String,
            val type: TypeName,
            val isSingle: Boolean,
            val isRetain: Boolean,
            val propertyType: TypeName
    )

    fun onSaveInstanceStateFunc() = FunSpec.builder("onSaveInstanceState")
            .addParameter(ParameterSpec.builder("bundle", ClassName("android.os", "Bundle").asNullable()).build())

    private fun buildObservableInterface(properties: List<Property>, name: String, isParcelable: Boolean): TypeSpec.Builder {
        val outInterface = TypeSpec.interfaceBuilder(name)
        properties.forEach { property ->
            val func = FunSpec.builder("${property.name}Changes")
                    .addModifiers(KModifier.ABSTRACT)
                    .returns(ClassName("io.reactivex", "Observable")
                            .parameterizedBy(property.type))
                    .build()
            outInterface.addFunction(func)
        }

        if (isParcelable) {
            outInterface.addFunction(onSaveInstanceStateFunc().addModifiers(KModifier.ABSTRACT).build())
        }
        return outInterface
    }

    private fun buildOutClass(properties: List<Property>, name: String, isParcelable: Boolean): TypeSpec.Builder? {
        if (!isParcelable) return null

        val constructorBuilder = FunSpec.constructorBuilder()
        val type = TypeSpec.classBuilder(name)
        properties.filter { it.isRetain }.forEach {
            constructorBuilder.addParameter(it.name, it.propertyType)
            type.addProperty(PropertySpec.builder(it.name, it.propertyType).initializer(it.name).build())
        }
        return type
                .addModifiers(KModifier.DATA)
                .primaryConstructor(constructorBuilder.build())
                .addAnnotation(AnnotationSpec.builder(ClassName.bestGuess("kotlinx.android.parcel.Parcelize")).build())
                .addSuperinterface(ClassName.bestGuess("android.os.Parcelable"))
    }

    private fun getEventClassName(isSingle: Boolean): TypeName = ClassName(
            "com.belax.annotation", if (isSingle) "SingleEvent" else "SimpleEvent"
    )

    private val writeDir get() = File(processingEnv.options["kapt.kotlin.generated"])

    private fun getInitialStateFun(name: String, defaultProperty: PropertySpec, properties: List<Property>, pStateType: TypeSpec?, key: String?): FunSpec {
        val default = FunSpec.builder("getInitialState")
                .addModifiers(KModifier.PRIVATE)
                .returns(defaultProperty.type)
        if (pStateType == null) {
            return default.addCode("return $name()\n").build()
        }
        return default
                .addParameter("bundle", ClassName("android.os", "Bundle").asNullable())
                .addCode("val bundledValue = bundle?.getParcelable($key) as? ${pStateType.name}\n" +
                        "return if (bundledValue == null) $name() else $name(\n" +
                        "${properties.asSequence().filter { it.isRetain }.joinToString(",\n") { "${it.name} = bundledValue.${it.name}" }})\n")
                .build()
    }

    private fun buildDelegate(element: Element, properties: List<Property>, outInterface: TypeSpec, name: String, pStateType: TypeSpec?): TypeSpec {
        val defaultName = "default"
        val defaultiIitializer = "getInitialState(${if (pStateType != null) "bundle" else ""})"

        val defaultProperty = PropertySpec.builder("default", ClassName.bestGuess(element.internalName.replace('/', '.')))
                .initializer(defaultiIitializer)
                .addModifiers(KModifier.PRIVATE)
                .build()

        // todo make is all in one loop

        val subjects = properties.map {
            val subjectClass = ClassName("io.reactivex.subjects", "BehaviorSubject")
                    .parameterizedBy(it.type)
            val eventClassName = getEventClassName(it.isSingle)
            return@map PropertySpec.builder("${it.name}Subject", subjectClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("BehaviorSubject.createDefault<%T>(%T($defaultName.${it.name}))", it.type, eventClassName)
                    .build()
        }

        val observables = properties.mapIndexed { index, property ->
            val className = ClassName("io.reactivex", "Observable")
                    .parameterizedBy(property.type)
            return@mapIndexed FunSpec.builder("${property.name}Changes")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(className)

                    .addCode("return ${subjects[index].name}.hide()\n")
                    .build()
        }

        val pushFunc = properties.mapIndexed { index, property ->
            val param = ParameterSpec.builder("value", property.propertyType).build()
            val eventClassName = getEventClassName(property.isSingle)
            return@mapIndexed FunSpec.builder("push${property.name.capitalize()}")
                    .addParameter(param)
                    .addCode("${subjects[index].name}.onNext(%T(value))\n", eventClassName)
                    .build()
        }
        val bundleKey = "BUNDLE_KEY"
        val delegate = TypeSpec.classBuilder(name)
                .addProperty(defaultProperty)
                .addFunction(getInitialStateFun(element.simpleName.toString(),defaultProperty, properties, pStateType, bundleKey))
                .addProperties(subjects)
                .addFunctions(observables)
                .addFunctions(pushFunc)
                .addSuperinterface(ClassName.bestGuess(outInterface.name!!))

        if (pStateType != null) {

            val constBundleProperty = PropertySpec.builder(bundleKey, ClassName.bestGuess("kotlin.String"))
                    .addModifiers(KModifier.PRIVATE, KModifier.CONST)
                    .initializer("\"${element.simpleName.toString().toUpperCase()}_$bundleKey\"")
                    .build()

            val companion = TypeSpec.companionObjectBuilder()
                    .addProperty(constBundleProperty)
                    .build()

            val bundleType = ClassName("android.os", "Bundle")

            val saveInstanceStateFunc = onSaveInstanceStateFunc()
                    .addModifiers(KModifier.OVERRIDE)
                    .addCode("val value = ${pStateType.name}(" +
                            "${properties.filter { it.isRetain }.joinToString(",\n\t") { "${it.name} = ${it.name}Subject.value!!.get()" }})\n" +
                            "bundle?.putParcelable($bundleKey, value)\n")
                    .build()

            val constructor = FunSpec.constructorBuilder()
                    .addParameter("bundle", bundleType.asNullable())
                    .build()

            delegate.primaryConstructor(constructor)
                    .addType(companion)
                    .addFunction(saveInstanceStateFunc)

        }

        return delegate.build()
    }

    override fun process(p0: MutableSet<out TypeElement>, p1: RoundEnvironment): Boolean {
        p1.getElementsAnnotatedWith(VMState::class.java).filter { it.kind == ElementKind.CLASS }.forEach { classElement ->
            val className = classElement.simpleName
            val outInterfaceFileName = "${className}Client"
            val delegateName = "${className}Delegate"
            val outClassFileName = "${className}_Parcelable"

            val classData = (classElement.kotlinMetadata as KotlinClassMetadata).data
            val proto = classData.proto
            val nameResolver = classData.nameResolver
            val constr = proto.constructorList.find { it.isPrimary }!!


            val properties = mutableListOf<Property>()

            classElement.enclosedElements.filter { it.kind.isField }.forEachIndexed { index, element ->

                val param = constr.valueParameterList[index]
                val name = nameResolver.getName(param.name)
                val propertyType = getProperty(param.type, nameResolver)
                val observableType = ClassName("com.belax.annotation",  "Event")
                        .parameterizedBy(propertyType)


                val isSingle = element.getAnnotation(Config::class.java)?.isSingle == true
                val isRetain = element.getAnnotation(Config::class.java)?.isRetain == true

                properties.add(Property(name.toString(), observableType, isSingle, isRetain, propertyType))
            }

            val isParcelable: Boolean = properties.any { it.isRetain }

            val outClass = buildOutClass(properties, outClassFileName, isParcelable)?.build()

            val outInterface = buildObservableInterface(properties, outInterfaceFileName, isParcelable).build()

            val outInterfaceFile = FileSpec.builder(processingEnv.elementUtils.getPackageOf(classElement).toString(), outInterfaceFileName)
                    .addType(outInterface).build()
            outInterfaceFile.writeTo(writeDir)

            outClass?.let {
                val outClassFile =  FileSpec.builder(processingEnv.elementUtils.getPackageOf(classElement).toString(), outClassFileName)
                        .addType(it).build()
                outClassFile.writeTo(writeDir)
            }

            val delegate = buildDelegate(classElement, properties, outInterface, delegateName, outClass)

            val viewModelDelegateFile = FileSpec.builder(processingEnv.elementUtils.getPackageOf(classElement).toString(), delegateName)
                    .addType(delegate).build()
            viewModelDelegateFile.writeTo(writeDir)

        }

        return false
    }

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(VMState::class.java.name)
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latest()
    }

    private fun log(msg: String) {
        messager.printMessage(Diagnostic.Kind.ERROR, msg)
    }
}

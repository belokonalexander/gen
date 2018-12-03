package com.belax.generator

import com.belax.annotation.Single
import com.belax.annotation.VMState
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import java.io.File
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import me.eugeniomarletti.kotlin.metadata.*
import me.eugeniomarletti.kotlin.metadata.shadow.serialization.deserialization.getName
import java.util.*
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.ElementKind
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic


@AutoService(Processor::class)
class Generator : AbstractProcessor() {

    private lateinit var messager: Messager
    private lateinit var filer: Filer
    private val genericSolver = GenericSolver()

    override fun init(p0: ProcessingEnvironment) {
        super.init(p0)
        messager = p0.messager
        filer = p0.filer

    }

    override fun process(p0: MutableSet<out TypeElement>, p1: RoundEnvironment): Boolean {
        p1.getElementsAnnotatedWith(VMState::class.java).filter { it.kind == ElementKind.CLASS }.forEach {
            val className = it.simpleName
            val outInterfaceFileName = "Generated_${className}Observable"
            val delegateName = "${className}Delegate"
            val outInterface = TypeSpec.interfaceBuilder(outInterfaceFileName)

            val properties = mutableListOf<PropertySpec>()

            val classData = (it.kotlinMetadata as KotlinClassMetadata).data
            val proto = classData.proto
            val nameResolver = classData.nameResolver



            val constr = proto.constructorList.find { it.isPrimary }!!




            it.enclosedElements.filter { it.kind.isField }.forEachIndexed { index, element ->

                val param = constr.valueParameterList[index]
                val name = nameResolver.getName(param.name)
                val arguments = param.type.argumentList
                val instance = param.defaultInstanceForType

                log(" ----> ${name} = ${nameResolver.getName(param.type.className)}<${arguments.joinToString { nameResolver.getName(it.type.className).toString() }}> -----> " +
                        " = ${nameResolver.getName(instance.name)}")

            }

            /*it.enclosedElements.filter { it.kind.isField }.forEach {
                val fieldClassName = it.asType().asTypeName().toString()
                val isSingle = it.getAnnotation(Single::class.java) != null
                val propertyType = genericSolver.run(fieldClassName)
                val eventPropertyType = if (isSingle) "SingleEvent" else "SimpleEvent"


                val defaultValue = ""
                val observableType = ClassName("com.belax.annotation",  "Event")
                        .parameterizedBy(propertyType)

                val func = FunSpec.builder("${it.simpleName}Changes")
                        .addModifiers(KModifier.ABSTRACT)
                        .returns(ClassName("io.reactivex", "Observable")
                                .parameterizedBy(observableType))
                        .build()

                val subjectType = ClassName("com.belax.annotation", eventPropertyType)
                        .parameterizedBy(propertyType)
                val subjectClass = ClassName("io.reactivex.subjects", "BehaviorSubject")
                        .parameterizedBy(subjectType)
                properties.add(PropertySpec.builder("${it.simpleName}Subject", subjectClass)
                        .initializer("BehaviorSubject.createDefault<$subjectType>($eventPropertyType($defaultValue))")
                        .build())

                outInterface.addFunction(func)
            }

            val delegate = TypeSpec.classBuilder(delegateName)
                    .addProperties(properties)
                    .addSuperinterface(ClassName.bestGuess(outInterfaceFileName))

            val outInterfaceFile = FileSpec.builder(processingEnv.elementUtils.getPackageOf(it).toString(), outInterfaceFileName)
                    .addType(outInterface.build()).build()

            val viewModelDelegateFile = FileSpec.builder(processingEnv.elementUtils.getPackageOf(it).toString(), delegateName)
                    .addType(delegate.build()).build()

            outInterfaceFile.writeTo(File(processingEnv.options["kapt.kotlin.generated"]))
            //viewModelDelegateFile.writeTo(File(processingEnv.options["kapt.kotlin.generated"]))*/
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

sealed class Token {
    open fun length(): Int = 1
}

class Open : Token()
class Close : Token()
class Delimiter : Token()
class Value(val value: TypeName) : Token() {
    private var raw: String? = null
    constructor(raw: String) : this(ClassName.bestGuess(raw.trim())) {
        this.raw = raw
    }
    override fun length(): Int = raw?.length ?: 0
}

class GenericSolver (
        //private val sum: (List<Value>) -> Value,
        private val form: (Value, List<Value>) -> Value = { main, ch ->
            val childs = ch.map { it.value }
            when (main.value) {
                is ClassName -> Value(main.value.parameterizedBy(*childs.toTypedArray()))
                is ParameterizedTypeName -> Value(main.value.rawType.parameterizedBy(*childs.toTypedArray()))
                else -> throw RuntimeException()
            }
        }
) {

    fun run(command: String): TypeName {
        var i = 0
        val stack: Stack<Token> = Stack()
        while (i < command.length) {
            val item = takeItem(command.drop(i))
            i += item.length()

            if (item is Delimiter) continue

            if (item !is Close) {
                stack.push(item)
            } else {
                val list = mutableListOf<Value>()
                while (1==1) {
                    val v = stack.pop()
                    if (v is Value) list.add(v) else break

                }
                //val outer = sum(list)
                val main = stack.pop() as Value
                val result = form(main, list.reversed())
                stack.push(result)

            }
        }
        return (stack.pop() as Value).value
    }

    fun takeItem(command: String): Token {
        val first = command.first()

        return when(first) {
            '<' -> Open()
            '>' -> Close()
            ',' -> Delimiter()
            else -> Value(command.takeWhile { it !in listOf(',', '<', '>') })
        }
    }

}
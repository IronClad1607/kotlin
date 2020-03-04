/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.codegen

import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.backend.jvm.lower.MultifileFacadeFileEntry
import org.jetbrains.kotlin.backend.jvm.lower.buildAssertionsDisabledField
import org.jetbrains.kotlin.backend.jvm.lower.hasAssertionsDisabledField
import org.jetbrains.kotlin.codegen.*
import org.jetbrains.kotlin.codegen.binding.CodegenBinding
import org.jetbrains.kotlin.codegen.inline.*
import org.jetbrains.kotlin.codegen.serialization.JvmSerializationBindings
import org.jetbrains.kotlin.codegen.serialization.JvmSerializerExtension
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.WrappedClassDescriptor
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.java.JvmAnnotationNames
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.jvm.AsmTypes
import org.jetbrains.kotlin.resolve.jvm.annotations.JVM_SYNTHETIC_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.resolve.jvm.annotations.TRANSIENT_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.resolve.jvm.annotations.VOLATILE_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.resolve.jvm.checkers.JvmSimpleNameBacktickChecker
import org.jetbrains.kotlin.resolve.jvm.diagnostics.*
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmClassSignature
import org.jetbrains.kotlin.serialization.DescriptorSerializer
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.commons.Method
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import java.io.File

data class MaybeUnfinishedMethodNode(val node: MethodNode, val done: Boolean)

class ClassCodegen private constructor(
    internal val irClass: IrClass,
    val context: JvmBackendContext,
    private val parentFunction: IrFunction? = null
) : InnerClassConsumer {
    private val parentClassCodegen = (parentFunction?.parentAsClass ?: irClass.parent as? IrClass)?.let { getOrCreate(it, context) }
    private val withinInline: Boolean = parentClassCodegen?.withinInline == true || parentFunction?.isInline == true

    val state get() = context.state
    val typeMapper get() = context.typeMapper

    val type: Type = typeMapper.mapClass(irClass)

    val reifiedTypeParametersUsages = ReifiedTypeParametersUsages()

    private val jvmSignatureClashDetector = JvmSignatureClashDetector(irClass, type, context)
    private val classOrigin = run {
        // The descriptor associated with an IrClass is never modified in lowerings, so it
        // doesn't reflect the state of the lowered class. To make the diagnostics work we
        // pass in a wrapped descriptor instead.
        // TODO: Migrate class builders away from descriptors
        val descriptor = WrappedClassDescriptor().apply { bind(irClass) }
        val psiElement = context.psiSourceManager.findPsiElement(irClass)
        when (irClass.origin) {
            IrDeclarationOrigin.FILE_CLASS ->
                JvmDeclarationOrigin(JvmDeclarationOriginKind.PACKAGE_PART, psiElement, descriptor)
            else ->
                OtherOrigin(psiElement, descriptor)
        }
    }

    val visitor: ClassBuilder = state.factory.newVisitor(classOrigin, type, irClass.fileParent.loadSourceFilesInfo()).apply {
        val signature = getSignature(irClass, type, irClass.getSuperClassInfo(typeMapper), typeMapper)
        // Ensure that the backend only produces class names that would be valid in the frontend for JVM.
        if (context.state.classBuilderMode.generateBodies && signature.hasInvalidName()) {
            throw IllegalStateException("Generating class with invalid name '${type.className}': ${irClass.dump()}")
        }
        defineClass(
            irClass.descriptor.psiElement,
            state.classFileVersion,
            irClass.flags,
            signature.name,
            signature.javaGenericSignature,
            signature.superclassName,
            signature.interfaces.toTypedArray()
        )
    }

    val sourceMapper: DefaultSourceMapper by lazy { context.getSourceMapper(irClass) }
    var sourceMapShouldBeWritten = withinInline

    private val serializerExtension = JvmSerializerExtension(visitor.serializationBindings, state, typeMapper)
    private val serializer: DescriptorSerializer? =
        when (val metadata = irClass.metadata) {
            is MetadataSource.Class -> DescriptorSerializer.create(metadata.descriptor, serializerExtension, parentClassCodegen?.serializer)
            is MetadataSource.File -> DescriptorSerializer.createTopLevel(serializerExtension)
            is MetadataSource.Function -> DescriptorSerializer.createForLambda(serializerExtension)
            else -> null
        }

    private var regeneratedObjectNameGenerators = mutableMapOf<String, NameGenerator>()

    fun getRegeneratedObjectNameGenerator(function: IrFunction): NameGenerator {
        val name = if (function.name.isSpecial) "special" else function.name.asString()
        return regeneratedObjectNameGenerators.getOrPut(name) {
            NameGenerator("${type.internalName}\$$name\$\$inlined")
        }
    }

    private var hasAssertField = irClass.hasAssertionsDisabledField(context)
    private var classInitializer = irClass.functions.singleOrNull { it.name.asString() == "<clinit>" }
    private var generatingClInit = false
    private var generated = false

    fun generate(): ReifiedTypeParametersUsages {
        // TODO: reject repeated generate() calls; currently, these can happen for objects in finally
        //       blocks since they are `accept`ed once per each CFG edge out of the try-finally.
        if (generated) return reifiedTypeParametersUsages
        generated = true

        for (declaration in irClass.declarations) {
            when (declaration) {
                classInitializer, is IrClass -> Unit // see below
                is IrField -> generateField(declaration)
                is IrFunction -> generateMethod(declaration)
                else -> throw AssertionError("unexpected class member type $declaration at codegen")
            }
        }

        // Delay generation of <clinit> until the end because inline function calls
        // might need to generate the `$assertionsDisabled` field initializer.
        classInitializer?.let {
            generatingClInit = true
            generateMethod(it)
        }

        // Generate nested classes at the end, to ensure that when the companion's metadata is serialized
        // everything moved to the outer class has already been recorded in `globalSerializationBindings`.
        for (declaration in irClass.declarations) {
            if (declaration is IrClass) {
                getOrCreate(declaration, context).generate()
            }
        }

        object : AnnotationCodegen(this@ClassCodegen, context) {
            override fun visitAnnotation(descr: String?, visible: Boolean): AnnotationVisitor {
                return visitor.visitor.visitAnnotation(descr, visible)
            }
        }.genAnnotations(irClass, null, null)
        generateKotlinMetadataAnnotation()

        val fileEntry = context.psiSourceManager.getFileEntry(irClass.fileParent)
        if (fileEntry != null) {
            /* TODO: Temporary workaround: ClassBuilder needs a pathless name. */
            val shortName = File(fileEntry.name).name
            visitor.visitSource(shortName, null)
        }

        // JVMS7 (4.7.6): a nested class or interface member will have InnerClasses information
        // for each enclosing class and for each immediate member
        parentClassCodegen?.let { writeInnerClass(irClass, typeMapper, context, it.visitor) }
        for (codegen in generateSequence(this) { it.parentClassCodegen }.takeWhile { it.parentClassCodegen != null }) {
            writeInnerClass(codegen.irClass, typeMapper, context, visitor)
        }

        // JVMS7 (4.7.7): A class must have an EnclosingMethod attribute if and only if
        // it is a local class or an anonymous class.
        //
        // The attribute contains the innermost class that encloses the declaration of
        // the current class. If the current class is immediately enclosed by a method
        // or constructor, the name and type of the function is recorded as well.
        if (parentClassCodegen != null) {
            val enclosingFunction = context.customEnclosingFunction[irClass.attributeOwnerId] ?: parentFunction
            if (enclosingFunction != null || irClass.isAnonymousObject) {
                val method = enclosingFunction?.let(context.methodSignatureMapper::mapAsmMethod)
                visitor.visitOuterClass(parentClassCodegen.type.internalName, method?.name, method?.descriptor)
            }
        }

        if (sourceMapShouldBeWritten) {
            SourceMapper.flushToClassBuilder(sourceMapper, visitor)
        }

        jvmSignatureClashDetector.reportErrors(classOrigin)

        visitor.done()
        return reifiedTypeParametersUsages
    }

    override fun addInnerClassInfoFromAnnotation(innerClass: IrClass) {
        // It's necessary for proper recovering of classId by plain string JVM descriptor when loading annotations
        // See FileBasedKotlinClass.convertAnnotationVisitor
        generateSequence<IrDeclaration>(innerClass) { it.parent as? IrDeclaration }.takeWhile { !it.isTopLevelDeclaration }.forEach {
            if (it is IrClass) {
                writeInnerClass(it, typeMapper, context, visitor)
            }
        }
    }

    fun generateAssertFieldIfNeeded(): IrExpression? {
        if (hasAssertField)
            return null
        hasAssertField = true
        val topLevelClass = generateSequence(this) { it.parentClassCodegen }.last().irClass
        val field = irClass.buildAssertionsDisabledField(context, topLevelClass)
        generateField(field)
        // Normally, `InitializersLowering` would move the initializer to <clinit>, but
        // it's obviously too late for that.
        val init = IrSetFieldImpl(
            field.startOffset, field.endOffset, field.symbol, null,
            field.initializer!!.expression, context.irBuiltIns.unitType
        )
        if (classInitializer == null) {
            classInitializer = buildFun {
                name = Name.special("<clinit>")
                returnType = context.irBuiltIns.unitType
            }.apply {
                parent = irClass
                body = IrBlockBodyImpl(startOffset, endOffset)
            }
            // Do not add it to `irClass.declarations` to avoid a concurrent modification error.
        } else if (generatingClInit) {
            // Not only `classInitializer` is non-null, we're in fact generating it right now.
            // Attempting to do `body.statements.add` will cause a concurrent modification error,
            // so the currently active ExpressionCodegen needs to be asked to generate this
            // initializer directly.
            return init
        }
        (classInitializer!!.body as IrBlockBody).statements.add(0, init)
        return null
    }

    private fun generateKotlinMetadataAnnotation() {
        val localDelegatedProperties = (irClass.attributeOwnerId as? IrClass)?.let(context.localDelegatedProperties::get)
        if (localDelegatedProperties != null && localDelegatedProperties.isNotEmpty()) {
            state.bindingTrace.record(CodegenBinding.DELEGATED_PROPERTIES_WITH_METADATA, type, localDelegatedProperties.map { it.descriptor })
        }

        // TODO: if `-Xmultifile-parts-inherit` is enabled, write the corresponding flag for parts and facades to [Metadata.extraInt].
        var extraFlags = JvmAnnotationNames.METADATA_JVM_IR_FLAG
        if (state.isIrWithStableAbi) {
            extraFlags += JvmAnnotationNames.METADATA_JVM_IR_STABLE_ABI_FLAG
        }

        when (val metadata = irClass.metadata) {
            is MetadataSource.Class -> {
                val classProto = serializer!!.classProto(metadata.descriptor).build()
                writeKotlinMetadata(visitor, state, KotlinClassHeader.Kind.CLASS, extraFlags) {
                    AsmUtil.writeAnnotationData(it, serializer, classProto)
                }

                assert(irClass !in context.classNameOverride) {
                    "JvmPackageName is not supported for classes: ${irClass.render()}"
                }
            }
            is MetadataSource.File -> {
                val packageFqName = irClass.getPackageFragment()!!.fqName
                val packageProto = serializer!!.packagePartProto(packageFqName, metadata.descriptors)

                serializerExtension.serializeJvmPackage(packageProto, type)

                val facadeClassName = context.multifileFacadeForPart[irClass.attributeOwnerId]
                val kind = if (facadeClassName != null) KotlinClassHeader.Kind.MULTIFILE_CLASS_PART else KotlinClassHeader.Kind.FILE_FACADE
                writeKotlinMetadata(visitor, state, kind, extraFlags) { av ->
                    AsmUtil.writeAnnotationData(av, serializer, packageProto.build())

                    if (facadeClassName != null) {
                        av.visit(JvmAnnotationNames.METADATA_MULTIFILE_CLASS_NAME_FIELD_NAME, facadeClassName.internalName)
                    }

                    if (irClass in context.classNameOverride) {
                        av.visit(JvmAnnotationNames.METADATA_PACKAGE_NAME_FIELD_NAME, irClass.fqNameWhenAvailable!!.parent().asString())
                    }
                }
            }
            is MetadataSource.Function -> {
                val fakeDescriptor = createFreeFakeLambdaDescriptor(metadata.descriptor)
                val functionProto = serializer!!.functionProto(fakeDescriptor)?.build()
                writeKotlinMetadata(visitor, state, KotlinClassHeader.Kind.SYNTHETIC_CLASS, extraFlags) {
                    if (functionProto != null) {
                        AsmUtil.writeAnnotationData(it, serializer, functionProto)
                    }
                }
            }
            else -> {
                val entry = irClass.fileParent.fileEntry
                if (entry is MultifileFacadeFileEntry) {
                    val partInternalNames = entry.partFiles.mapNotNull { partFile ->
                        val fileClass = partFile.declarations.singleOrNull { it.isFileClass } as IrClass?
                        if (fileClass != null) typeMapper.mapClass(fileClass).internalName else null
                    }
                    MultifileClassCodegenImpl.writeMetadata(
                        visitor, state, extraFlags, partInternalNames, type, irClass.fqNameWhenAvailable!!.parent()
                    )
                } else {
                    writeSyntheticClassMetadata(visitor, state)
                }
            }
        }
    }

    private fun IrFile.loadSourceFilesInfo(): List<File> {
        val entry = fileEntry
        if (entry is MultifileFacadeFileEntry) {
            return entry.partFiles.flatMap { it.loadSourceFilesInfo() }
        }
        return listOfNotNull(context.psiSourceManager.getFileEntry(this)?.let { File(it.name) })
    }

    companion object {
        fun getOrCreate(irClass: IrClass, context: JvmBackendContext, parentFunction: IrFunction? = null): ClassCodegen =
            context.classCodegens.getOrPut(irClass) {
                ClassCodegen(irClass, context, parentFunction)
            }.also {
                assert(parentFunction == null || it.parentFunction == parentFunction) {
                    "inconsistent parent function for ${irClass.render()}:\n" +
                            "New: ${parentFunction!!.render()}\n" +
                            "Old: ${it.parentFunction?.render()}"
                }
            }

        private fun JvmClassSignature.hasInvalidName() =
            name.splitToSequence('/').any { identifier -> identifier.any { it in JvmSimpleNameBacktickChecker.INVALID_CHARS } }
    }

    private fun generateField(field: IrField) {
        if (field.origin == IrDeclarationOrigin.FAKE_OVERRIDE) return

        val fieldType = typeMapper.mapType(field)
        val fieldSignature =
            if (field.origin == IrDeclarationOrigin.PROPERTY_DELEGATE) null
            else context.methodSignatureMapper.mapFieldSignature(field)
        val fieldName = field.name.asString()
        val fv = visitor.newField(
            field.OtherOrigin, field.flags, fieldName, fieldType.descriptor,
            fieldSignature, (field.initializer?.expression as? IrConst<*>)?.value
        )

        jvmSignatureClashDetector.trackField(field, RawSignature(fieldName, fieldType.descriptor, MemberKind.FIELD))

        if (field.origin != JvmLoweredDeclarationOrigin.CONTINUATION_CLASS_RESULT_FIELD) {
            object : AnnotationCodegen(this@ClassCodegen, context) {
                override fun visitAnnotation(descr: String?, visible: Boolean): AnnotationVisitor {
                    return fv.visitAnnotation(descr, visible)
                }

                override fun visitTypeAnnotation(descr: String?, path: TypePath?, visible: Boolean): AnnotationVisitor {
                    return fv.visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.FIELD).value,path, descr, visible)
                }
            }.genAnnotations(field, fieldType, field.type)
        }

        val descriptor = field.metadata?.descriptor
        if (descriptor != null) {
            state.globalSerializationBindings.put(JvmSerializationBindings.FIELD_FOR_PROPERTY, descriptor, fieldType to fieldName)
        }
    }

    private val generatedInlineMethods = mutableMapOf<IrFunction, MaybeUnfinishedMethodNode>()

    private fun generateMethodNode(method: IrFunction, prototype: MethodNode?): MaybeUnfinishedMethodNode =
        generatedInlineMethods.getOrPut(method) {
            val codegen = FunctionCodegen(method, this)
            // Inline functions can be used several times within the module (see IrSourceCompilerForInline).
            // Cache the empty node before proceeding in order to handle inline call cycles.
            generatedInlineMethods[method] = MaybeUnfinishedMethodNode(codegen.methodNode, false)
            MaybeUnfinishedMethodNode(codegen.generate(prototype), true)
        }

    fun generateMethodNode(method: IrFunction): MaybeUnfinishedMethodNode {
        assert(method.parentAsClass == irClass)
        // `$$forInline` versions of suspend functions have the same bytecode as the original,
        // but different annotations/flags/etc. and no state machine.
        // TODO avoid caching non-inline functions with no $$forInline version (those are only needed once).
        val original = method.suspendForInlineToOriginal()?.let { generateMethodNode(it, null) }
        if (original?.done == false) {
            // `inline suspend fun f` calls `inline suspend fun g` calls `f` (through `$$forInline`).
            return MaybeUnfinishedMethodNode(FunctionCodegen(method, this).methodNode, false)
        }
        val result = generateMethodNode(method, original?.node)
        // Copying the node is needed for two reasons:
        //   1. if this function is called from the inliner, it will modify the returned node;
        //   2. it's easier to write `preprocessSuspendMarkers` this way instead of a `MethodVisitor`.
        val copy = with(result.node) { MethodNode(Opcodes.API_VERSION, access, name, desc, signature, exceptions.toTypedArray()) }
        if (result.done) {
            result.node.instructions.resetLabels()
            result.node.accept(copy)
            copy.preprocessSuspendMarkers(method)
        }
        return MaybeUnfinishedMethodNode(copy, result.done)
    }

    private fun generateMethod(method: IrFunction) {
        if (method.origin == IrDeclarationOrigin.FAKE_OVERRIDE) {
            jvmSignatureClashDetector.trackFakeOverrideMethod(method)
            return
        }

        val node = generateMethodNode(method).node
        val methodWriter = with(node) {
            visitor.newMethod(method.OtherOrigin, access, name, desc, signature, exceptions.toTypedArray())
        }
        if (method.hasContinuation() || method.isInvokeSuspendOfLambda()) {
            // Generate a state machine within this method. The continuation class for it should be generated
            // lazily so that if tail call optimization kicks in, the unused class will not be written to the output.
            val continuationClassCodegen = lazy { getOrCreate(method.continuationClass()!!, context, method) }
            node.acceptWithStateMachine(method, this, methodWriter) {
                if (method.isSuspend) continuationClassCodegen.value.visitor else visitor
            }
            if (continuationClassCodegen.isInitialized() || method.alwaysNeedsContinuation()) {
                continuationClassCodegen.value.generate()
            }
        } else {
            node.accept(methodWriter)
        }
        jvmSignatureClashDetector.trackMethod(method, RawSignature(node.name, node.desc, MemberKind.METHOD))

        val signature = Method(node.name, node.desc)
        when (val metadata = method.metadata) {
            is MetadataSource.Property -> {
                // We can't check for JvmLoweredDeclarationOrigin.SYNTHETIC_METHOD_FOR_PROPERTY_ANNOTATIONS because for interface methods
                // moved to DefaultImpls, origin is changed to DEFAULT_IMPLS
                // TODO: fix origin somehow, because otherwise $annotations methods in interfaces also don't have ACC_SYNTHETIC
                assert(method.name.asString().endsWith(JvmAbi.ANNOTATED_PROPERTY_METHOD_NAME_SUFFIX)) { method.dump() }

                state.globalSerializationBindings.put(
                    JvmSerializationBindings.SYNTHETIC_METHOD_FOR_PROPERTY, metadata.descriptor, signature
                )
            }
            is MetadataSource.Function -> {
                visitor.serializationBindings.put(JvmSerializationBindings.METHOD_FOR_FUNCTION, metadata.descriptor, signature)
            }
            null -> {
            }
            else -> error("Incorrect metadata source $metadata for:\n${method.dump()}")
        }
    }
}

private val IrClass.flags: Int
    get() = origin.flags or getVisibilityAccessFlagForClass() or deprecationFlags or when {
        isAnnotationClass -> Opcodes.ACC_ANNOTATION or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
        isInterface -> Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
        isEnumClass -> Opcodes.ACC_ENUM or Opcodes.ACC_SUPER or modality.flags
        else -> Opcodes.ACC_SUPER or modality.flags
    }

private val IrField.flags: Int
    get() = origin.flags or visibility.flags or (correspondingPropertySymbol?.owner?.deprecationFlags ?: 0) or
            (if (isFinal) Opcodes.ACC_FINAL else 0) or
            (if (isStatic) Opcodes.ACC_STATIC else 0) or
            (if (hasAnnotation(VOLATILE_ANNOTATION_FQ_NAME)) Opcodes.ACC_VOLATILE else 0) or
            (if (hasAnnotation(TRANSIENT_ANNOTATION_FQ_NAME)) Opcodes.ACC_TRANSIENT else 0) or
            (if (hasAnnotation(JVM_SYNTHETIC_ANNOTATION_FQ_NAME)) Opcodes.ACC_SYNTHETIC else 0)

private val IrDeclarationOrigin.flags: Int
    get() = (if (isSynthetic) Opcodes.ACC_SYNTHETIC else 0) or
            (if (this == IrDeclarationOrigin.FIELD_FOR_ENUM_ENTRY) Opcodes.ACC_ENUM else 0)

private val Modality.flags: Int
    get() = when (this) {
        Modality.ABSTRACT, Modality.SEALED -> Opcodes.ACC_ABSTRACT
        Modality.FINAL -> Opcodes.ACC_FINAL
        Modality.OPEN -> 0
        else -> throw AssertionError("Unsupported modality $this")
    }

private val Visibility.flags: Int
    get() = AsmUtil.getVisibilityAccessFlag(this) ?: throw AssertionError("Unsupported visibility $this")

// For lambdas, produce a descriptor which refers back to the original function
internal val IrDeclaration.OtherOrigin: JvmDeclarationOrigin
    get() {
        val klass = (this as? IrClass) ?: parentAsClass
        return OtherOrigin(
            if (klass.origin == JvmLoweredDeclarationOrigin.LAMBDA_IMPL || klass.origin == JvmLoweredDeclarationOrigin.SUSPEND_LAMBDA) {
                (klass.attributeOwnerId as IrFunctionReference).symbol.descriptor
            } else {
                descriptor
            }
        )
    }

private fun IrClass.getSuperClassInfo(typeMapper: IrTypeMapper): IrSuperClassInfo {
    if (isInterface) {
        return IrSuperClassInfo(AsmTypes.OBJECT_TYPE, null)
    }

    for (superType in superTypes) {
        val superClass = superType.safeAs<IrSimpleType>()?.classifier?.safeAs<IrClassSymbol>()?.owner
        if (superClass != null && !superClass.isJvmInterface) {
            return IrSuperClassInfo(typeMapper.mapClass(superClass), superType)
        }
    }

    return IrSuperClassInfo(AsmTypes.OBJECT_TYPE, null)
}

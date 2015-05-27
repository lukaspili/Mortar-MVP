package mvp.compiler.processingstep;

import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import org.apache.commons.lang3.StringUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.Filer;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import dagger.Component;
import mvp.AutoScreen;
import mvp.ScreenParam;
import mvp.compiler.MisunderstoodPoet;
import mvp.compiler.extractor.ScreenExtractor;
import mvp.compiler.message.Message;
import mvp.compiler.message.MessageDelivery;
import mvp.compiler.model.Configuration;
import mvp.compiler.model.InjectableVariableElement;
import mvp.compiler.model.spec.AutoComponentMemberSpec;
import mvp.compiler.model.spec.ConfigSpec;
import mvp.compiler.model.spec.ModuleSpec;
import mvp.compiler.model.spec.ScreenAnnotationSpec;
import mvp.compiler.model.spec.ScreenSpec;
import mvp.compiler.names.ClassNames;

/**
 * @author Lukasz Piliszczuk <lukasz.pili@gmail.com>
 */
public class ScreenProcessingStep implements ProcessingStep {

    private final Types types;
    private final Elements elements;
    private final Filer filer;
    private final MessageDelivery messageDelivery;
    private final ProcessingStepsBus processingStepsBus;
    private final MisunderstoodPoet misunderstoodPoet;

    public ScreenProcessingStep(Types types, Elements elements, Filer filer, MessageDelivery messageDelivery, ProcessingStepsBus processingStepsBus) {
        Preconditions.checkNotNull(types);
        Preconditions.checkNotNull(elements);
        Preconditions.checkNotNull(filer);
        Preconditions.checkNotNull(messageDelivery);
        Preconditions.checkNotNull(processingStepsBus);

        this.types = types;
        this.elements = elements;
        this.filer = filer;
        this.messageDelivery = messageDelivery;
        this.processingStepsBus = processingStepsBus;

        misunderstoodPoet = new MisunderstoodPoet();
    }

    @Override
    public Class<? extends Annotation> annotation() {
        return AutoScreen.class;
    }

    @Override
    public void process(Set<? extends Element> elements) {
        System.out.println("@AutoScreen Processing Step");
        // get user configuration, or default one if none provided
        Configuration configuration = processingStepsBus.getConfiguration();
        if (configuration == null) {
            configuration = Configuration.defaultConfig();
        }

        List<ScreenSpec> screenSpecs = new ArrayList<>();

        for (Element element : elements) {
            ScreenExtractor screenExtractor = new ScreenExtractor(element, this.types, this.elements);

            boolean valid = validateElement(screenExtractor);
            if (!valid) {
                // do not try to build screen for already invalid element
                continue;
            }

            ClassNames classNames = new ClassNames(screenExtractor.getElement());

            ScreenSpec screenSpec = buildScreen(screenExtractor, classNames, configuration);
            Preconditions.checkNotNull(screenSpec);
            screenSpecs.add(screenSpec);
        }


        ConfigSpec configSpec = buildConfig(configuration);
        Preconditions.checkNotNull(configSpec, "Config spec is null");

        boolean valid = validateSpecs(screenSpecs, configSpec);
        if (valid) {
            generateSpecs(screenSpecs, configSpec);
        }
    }

    private ConfigSpec buildConfig(Configuration configuration) {
        ConfigSpec configSpec = new ConfigSpec(ClassNames.mvpConfig());
        configSpec.setDaggerServiceName(configuration.getDaggerServiceName());
        return configSpec;
    }

    private ScreenSpec buildScreen(ScreenExtractor screenExtractor, ClassNames classNames, Configuration configuration) {
        Preconditions.checkNotNull(screenExtractor);
        Preconditions.checkNotNull(classNames);

        System.out.println("Buid screen " + screenExtractor.getElement().getSimpleName());

        ScreenSpec screenSpec = new ScreenSpec(classNames.getScreenClassName(), screenExtractor.getElement());

        // screen presenter
        // which is the class on which the @AutoScreen annotation is applied
        screenSpec.setPresenterTypeName(TypeName.get(screenExtractor.getElement().asType()));

        // screen superclass
        screenSpec.setSuperclassTypeName(configuration.getScreenSuperclassTypeName());

        // dagger scope
        screenSpec.setScopeAnnotationMirror(screenExtractor.getScopeAnnotationTypeMirror());

        // component name
        screenSpec.setComponentClassName(classNames.getComponentClassName());

        // component dependencies
        screenSpec.setComponentDependenciesSpecs(buildComponentTypeNames(classNames, screenExtractor.getComponentDependencies(), true));

        // component modules, + add the screen module
        List<AutoComponentMemberSpec> modulesMemberSpecs = buildComponentTypeNames(classNames, screenExtractor.getComponentModules(), false);
        modulesMemberSpecs.add(new AutoComponentMemberSpec(classNames.getModuleClassName(), classNames.getModuleClassName(), StringUtils.uncapitalize(classNames.getModuleClassName().simpleName())));
        screenSpec.setComponentModulesSpecs(modulesMemberSpecs);

        // screen annotations
        List<ScreenAnnotationSpec> annotationSpecs = buildScreenAnnotationSpecs(screenExtractor);
        if (!annotationSpecs.isEmpty()) {
            screenSpec.setAnnotationSpecs(annotationSpecs);
        }

        List<InjectableVariableElement> injectableParams = buildInjectableParams(screenExtractor);
        Preconditions.checkNotNull(injectableParams);

        List<InjectableVariableElement> screenParamsMembers = Lists.newArrayList(Collections2.filter(injectableParams, new Predicate<InjectableVariableElement>() {
            @Override
            public boolean apply(InjectableVariableElement input) {
                return input.isScreenParam();
            }
        }));
        screenSpec.setScreenParamMembers(screenParamsMembers);

        // module
        ModuleSpec moduleSpec = buildModule(screenExtractor, screenSpec, classNames, injectableParams);
        screenSpec.setModuleSpec(moduleSpec);

        return screenSpec;
    }

    /**
     * @param dependencies true for dependencies, false for modules
     */
    private List<AutoComponentMemberSpec> buildComponentTypeNames(ClassNames classNames, List<TypeMirror> typeMirrors, boolean dependencies) {
        List<AutoComponentMemberSpec> memberSpecs = new ArrayList<>();
        if (typeMirrors == null) {
            return memberSpecs;
        }

        for (TypeMirror typeMirror : typeMirrors) {
            String name;
            TypeName typeName = TypeName.get(typeMirror);
            TypeName realTypeName;
            Element element = MoreTypes.asElement(typeMirror);

            if (dependencies) {
                // dependencies means type mirror is a component OR a class annotated with @AutoComponent / @AutoScreen
                if (MoreElements.isAnnotationPresent(element, Component.class)) {
                    // manual component, keep the name
                    name = StringUtils.uncapitalize(element.getSimpleName().toString());
                    realTypeName = typeName;
                } else {
                    // generated component, add _Component at the end
                    name = StringUtils.uncapitalize(String.format("%s_Component", element.getSimpleName().toString()));
                    realTypeName = ClassName.get(MoreElements.getPackage(element).toString(), String.format("%s_Component", element.getSimpleName().toString()));
                }
            } else {
                // module
                name = StringUtils.uncapitalize(element.getSimpleName().toString());
                realTypeName = typeName;
            }

            memberSpecs.add(new AutoComponentMemberSpec(typeName, realTypeName, name));
        }

        return memberSpecs;
    }

    private List<ScreenAnnotationSpec> buildScreenAnnotationSpecs(ScreenExtractor screenExtractor) {
        List<ScreenAnnotationSpec> annotationSpecs = new ArrayList<>();
        for (AnnotationMirror annotationTypeMirror : screenExtractor.getScreenAnnotationsMirrors()) {
            Element e = MoreTypes.asElement(annotationTypeMirror.getAnnotationType());
            TypeElement te = MoreElements.asType(e);
            ScreenAnnotationSpec spec = new ScreenAnnotationSpec(ClassName.get(te));
            annotationSpecs.add(spec);

            Map<? extends ExecutableElement, ? extends AnnotationValue> map = annotationTypeMirror.getElementValues();
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : map.entrySet()) {
                spec.getMembers().put(entry.getKey().getSimpleName().toString(), entry.getValue());
            }
        }

        return annotationSpecs;
    }

    private List<InjectableVariableElement> buildInjectableParams(ScreenExtractor screenExtractor) {
        List<InjectableVariableElement> injectableParams = new ArrayList<>();
        for (Element enclosedElement : screenExtractor.getElement().getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.CONSTRUCTOR) {
                for (VariableElement variableElement : MoreElements.asExecutable(enclosedElement).getParameters()) {
                    injectableParams.add(new InjectableVariableElement(variableElement, MoreElements.isAnnotationPresent(variableElement, ScreenParam.class)));
                }
            }
        }

        return injectableParams;
    }

    private ModuleSpec buildModule(ScreenExtractor screenExtractor, ScreenSpec screenSpec, ClassNames classNames, List<InjectableVariableElement> injectableParams) {
        Preconditions.checkNotNull(screenExtractor);
        Preconditions.checkNotNull(classNames);

        ModuleSpec moduleSpec = new ModuleSpec(classNames.getModuleClassName(), screenSpec);
        moduleSpec.setPresenterClassName(classNames.getPresenterClassName());
        moduleSpec.setProvidePresenterConstructorParams(injectableParams);

        List<InjectableVariableElement> methodParams = Lists.newArrayList(Collections2.filter(injectableParams, new Predicate<InjectableVariableElement>() {
            @Override
            public boolean apply(InjectableVariableElement input) {
                return !input.isScreenParam();
            }
        }));
        moduleSpec.setProvidePresenterParams(injectableParams);

        return moduleSpec;
    }

    private boolean validateElement(ScreenExtractor screenExtractor) {
        Preconditions.checkNotNull(screenExtractor);

        if (screenExtractor.isErrors()) {
            for (Message message : screenExtractor.getMessages()) {
                messageDelivery.add(message);
            }

            return false;
        }

        return true;
    }

    private boolean validateSpecs(List<ScreenSpec> screenSpecs, ConfigSpec configSpec) {
        Preconditions.checkNotNull(configSpec.getDaggerServiceName(), "Config dagger service name is null");

        // TODO: add validation
        return true;
    }

    private void generateSpecs(List<ScreenSpec> screenSpecs, ConfigSpec configSpec) {
        for (ScreenSpec screenSpec : screenSpecs) {
            TypeSpec typeSpec = misunderstoodPoet.compose(screenSpec);
            JavaFile javaFile = JavaFile.builder(screenSpec.getClassName().packageName(), typeSpec).build();
            write(javaFile, screenSpec.getElement());
        }

        // do not generate config classe twice, if there are multiple rounds
        if (!processingStepsBus.isConfigGenerated()) {
            processingStepsBus.setConfigGenerated(true);

            TypeSpec typeSpec = misunderstoodPoet.compose(configSpec);
            JavaFile javaFile = JavaFile.builder(configSpec.getClassName().packageName(), typeSpec).build();
            write(javaFile, null);
        }
    }

    private void write(JavaFile javaFile, Element element) {
        try {
            javaFile.writeTo(filer);
        } catch (Exception e) {
            StringWriter stackTrace = new StringWriter();
            e.printStackTrace(new PrintWriter(stackTrace));
            messageDelivery.add(Message.error(element, "Unable to generate class for %s. %s", javaFile.typeSpec.name, stackTrace));
        }
    }


}

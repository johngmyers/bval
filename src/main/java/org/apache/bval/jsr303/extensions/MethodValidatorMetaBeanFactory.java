/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.bval.jsr303.extensions;

import org.apache.bval.jsr303.ApacheFactoryContext;
import org.apache.bval.jsr303.AppendValidation;
import org.apache.bval.jsr303.Jsr303MetaBeanFactory;
import org.apache.bval.jsr303.util.SecureActions;
import org.apache.bval.model.Validation;
import org.apache.bval.util.AccessStrategy;
import org.apache.commons.lang.ClassUtils;

import javax.validation.Constraint;
import javax.validation.ConstraintValidator;
import javax.validation.Valid;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Description: extension to validate parameters/return values of
 * methods/constructors.<br/>
 */
// TODO RSt - move. this is an optional module: move the whole package. core
// code has no dependencies on it
public class MethodValidatorMetaBeanFactory extends Jsr303MetaBeanFactory {
    /**
     * Create a new MethodValidatorMetaBeanFactory instance.
     * 
     * @param factoryContext
     */
    public MethodValidatorMetaBeanFactory(ApacheFactoryContext factoryContext) {
        super(factoryContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean hasValidationConstraintsDefined(Method method) {
        return false;
    }

    /**
     * Finish building the specified {@link MethodBeanDescriptorImpl}.
     * 
     * @param descriptor
     */
    public void buildMethodDescriptor(MethodBeanDescriptorImpl descriptor) {
        try {
            buildMethodConstraints(descriptor);
            buildConstructorConstraints(descriptor);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private void buildConstructorConstraints(MethodBeanDescriptorImpl beanDesc) throws InvocationTargetException,
        IllegalAccessException {
        beanDesc.setConstructorConstraints(new HashMap<Constructor<?>, ConstructorDescriptor>());

        for (Constructor<?> cons : beanDesc.getMetaBean().getBeanClass().getDeclaredConstructors()) {
            if (!factoryContext.getFactory().getAnnotationIgnores().isIgnoreAnnotations(cons)) {

                ConstructorDescriptorImpl consDesc =
                    new ConstructorDescriptorImpl(beanDesc.getMetaBean(), new Validation[0]);
                beanDesc.putConstructorDescriptor(cons, consDesc);

                Annotation[][] paramsAnnos = cons.getParameterAnnotations();
                int idx = 0;
                for (Annotation[] paramAnnos : paramsAnnos) {
                    ParameterAccess access = new ParameterAccess(cons.getParameterTypes()[idx], idx);
                    processAnnotations(consDesc, paramAnnos, access, idx);
                    idx++;
                }
            }
        }
    }

    private void buildMethodConstraints(MethodBeanDescriptorImpl beanDesc) throws InvocationTargetException,
        IllegalAccessException {
        beanDesc.setMethodConstraints(new HashMap<Method, MethodDescriptor>());

        for (Method method : beanDesc.getMetaBean().getBeanClass().getDeclaredMethods()) {
            if (!factoryContext.getFactory().getAnnotationIgnores().isIgnoreAnnotations(method)) {

                MethodDescriptorImpl methodDesc = new MethodDescriptorImpl(beanDesc.getMetaBean(), new Validation[0]);
                beanDesc.putMethodDescriptor(method, methodDesc);

                // return value validations
                AppendValidationToList validations = new AppendValidationToList();
                ReturnAccess returnAccess = new ReturnAccess(method.getReturnType());
                for (Annotation anno : method.getAnnotations()) {
                    if (anno instanceof Valid) {
                        methodDesc.setCascaded(true);
                    } else {
                        processAnnotation(anno, methodDesc, returnAccess, validations);
                    }
                }
                methodDesc.addValidations(validations.getValidations());

                // parameter validations
                Annotation[][] paramsAnnos = method.getParameterAnnotations();
                int idx = 0;
                for (Annotation[] paramAnnos : paramsAnnos) {
                    ParameterAccess access = new ParameterAccess(method.getParameterTypes()[idx], idx);
                    processAnnotations(methodDesc, paramAnnos, access, idx);
                    idx++;
                }
            }
        }
    }

    private void processAnnotations(ProcedureDescriptor methodDesc, Annotation[] paramAnnos, AccessStrategy access,
        int idx) throws InvocationTargetException, IllegalAccessException {
        AppendValidationToList validations = new AppendValidationToList();
        boolean cascaded = false;
        for (Annotation anno : paramAnnos) {
            if (anno instanceof Valid) {
                cascaded = true;
            } else {
                processAnnotation(anno, methodDesc, access, validations);
            }
        }
        ParameterDescriptorImpl paramDesc =
            new ParameterDescriptorImpl(methodDesc.getMetaBean(), validations.getValidations().toArray(
                new Validation[validations.getValidations().size()]));
        paramDesc.setIndex(idx);
        paramDesc.setCascaded(cascaded);
        methodDesc.getParameterDescriptors().add(paramDesc);
    }

    private <A extends Annotation> void processAnnotation(A annotation, ProcedureDescriptor desc,
        AccessStrategy access, AppendValidation validations) throws InvocationTargetException, IllegalAccessException {

        if (annotation instanceof Valid) {
            desc.setCascaded(true);
        } else {
            Constraint vcAnno = annotation.annotationType().getAnnotation(Constraint.class);
            if (vcAnno != null) {
                Class<? extends ConstraintValidator<A, ?>>[] validatorClasses;
                validatorClasses = findConstraintValidatorClasses(annotation, vcAnno);
                applyConstraint(annotation, validatorClasses, null, ClassUtils.primitiveToWrapper((Class<?>) access
                    .getJavaType()), access, validations);
            } else {
                /**
                 * Multi-valued constraints
                 */
                Object result = SecureActions.getAnnotationValue(annotation, ANNOTATION_VALUE);
                if (result != null && result instanceof Annotation[]) {
                    for (Annotation each : (Annotation[]) result) {
                        processAnnotation(each, desc, access, validations); // recursion
                    }
                }
            }
        }
    }

}

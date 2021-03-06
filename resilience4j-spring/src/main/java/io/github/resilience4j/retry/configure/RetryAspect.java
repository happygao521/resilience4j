/*
 * Copyright 2019 Mahmoud Romeh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.resilience4j.retry.configure;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.annotation.Retry;
import io.vavr.CheckedFunction0;

/**
 * This Spring AOP aspect intercepts all methods which are annotated with a {@link Retry} annotation.
 * The aspect protects an annotated method with a Retry. The RetryRegistry is used to retrieve an instance of a Retry for
 * a specific name.
 */
@Aspect
public class RetryAspect implements Ordered {

	private static final Logger logger = LoggerFactory.getLogger(RetryAspect.class);
	private final static ScheduledExecutorService retryExecutorService = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());
	private final RetryConfigurationProperties retryConfigurationProperties;
	private final RetryRegistry retryRegistry;

	/**
	 * @param retryConfigurationProperties spring retry config properties
	 * @param retryRegistry                retry definition registry
	 */
	public RetryAspect(RetryConfigurationProperties retryConfigurationProperties, RetryRegistry retryRegistry) {
		this.retryConfigurationProperties = retryConfigurationProperties;
		this.retryRegistry = retryRegistry;
		cleanup();

	}

	@Pointcut(value = "@within(retry) || @annotation(retry)", argNames = "retry")
	public void matchAnnotatedClassOrMethod(Retry retry) {
	}

	@Around(value = "matchAnnotatedClassOrMethod(backendMonitored)", argNames = "proceedingJoinPoint, backendMonitored")
	public Object retryAroundAdvice(ProceedingJoinPoint proceedingJoinPoint, Retry backendMonitored) throws Throwable {
		Method method = ((MethodSignature) proceedingJoinPoint.getSignature()).getMethod();
		String methodName = method.getDeclaringClass().getName() + "#" + method.getName();
		if (backendMonitored == null) {
			backendMonitored = getBackendMonitoredAnnotation(proceedingJoinPoint);
		}
		String backend = backendMonitored.name();
		io.github.resilience4j.retry.Retry retry = getOrCreateRetry(methodName, backend);
		if (method.getReturnType().isInstance(CompletionStage.class) || method.getReturnType().isInstance(CompletableFuture.class)) {
			return handleAsyncJoinPoint(proceedingJoinPoint, retry, methodName);
		} else {
			return handleSyncJoinPoint(proceedingJoinPoint, retry, methodName);
		}
	}

	/**
	 * @param methodName the retry method name
	 * @param backend    the retry backend name
	 * @return the configured retry
	 */
	private io.github.resilience4j.retry.Retry getOrCreateRetry(String methodName, String backend) {
		io.github.resilience4j.retry.Retry retry = retryRegistry.retry(backend,
				() -> retryConfigurationProperties.createRetryConfig(backend));

		if (logger.isDebugEnabled()) {
			logger.debug("Created or retrieved retry '{}' with max attempts rate '{}'  for method: '{}'",
					backend, retry.getRetryConfig().getResultPredicate(), methodName);
		}
		return retry;
	}

	/**
	 * @param proceedingJoinPoint the aspect joint point
	 * @return the retry annotation
	 */
	private Retry getBackendMonitoredAnnotation(ProceedingJoinPoint proceedingJoinPoint) {
		if (logger.isDebugEnabled()) {
			logger.debug("circuitBreaker parameter is null");
		}
		Retry retry = null;
		Class<?> targetClass = proceedingJoinPoint.getTarget().getClass();
		if (targetClass.isAnnotationPresent(Retry.class)) {
			retry = targetClass.getAnnotation(Retry.class);
			if (retry == null && logger.isDebugEnabled()) {
				logger.debug("TargetClass has no annotation 'Retry'");
				retry = targetClass.getDeclaredAnnotation(Retry.class);
				if (retry == null && logger.isDebugEnabled()) {
					logger.debug("TargetClass has no declared annotation 'Retry'");
				}
			}
		}
		return retry;
	}

	/**
	 * @param proceedingJoinPoint the AOP logic joint point
	 * @param retry               the configured sync retry
	 * @param methodName          the retry method name
	 * @return the result object if any
	 * @throws Throwable
	 */
	private Object handleSyncJoinPoint(ProceedingJoinPoint proceedingJoinPoint, io.github.resilience4j.retry.Retry retry, String methodName) throws Throwable {
		if (logger.isDebugEnabled()) {
			logger.debug("retry invocation of method {} ", methodName);
		}
		final CheckedFunction0<Object> objectCheckedFunction0 = io.github.resilience4j.retry.Retry.decorateCheckedSupplier(retry, proceedingJoinPoint::proceed);
		return objectCheckedFunction0.apply();
	}

	/**
	 * @param proceedingJoinPoint the AOP logic joint point
	 * @param retry               the configured async retry
	 * @param methodName          the retry method name
	 * @return the result object if any
	 * @throws Throwable
	 */
	private Object handleAsyncJoinPoint(ProceedingJoinPoint proceedingJoinPoint, io.github.resilience4j.retry.Retry retry, String methodName) throws Throwable {
		if (logger.isDebugEnabled()) {
			logger.debug("async retry invocation of method {} ", methodName);
		}
		return io.github.resilience4j.retry.Retry.decorateCompletionStage(retry, retryExecutorService, () -> {
			try {
				return (CompletionStage<Object>) proceedingJoinPoint.proceed();
			} catch (Throwable throwable) {
				throw new CompletionException(throwable);
			}
		}).get();
	}


	@Override
	public int getOrder() {
		return retryConfigurationProperties.getRetryAspectOrder();
	}

	private void cleanup() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			retryExecutorService.shutdown();
			try {
				if (!retryExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
					retryExecutorService.shutdownNow();
				}
			} catch (InterruptedException e) {
				if (!retryExecutorService.isTerminated()) {
					retryExecutorService.shutdownNow();
				}
				Thread.currentThread().interrupt();
			}
		}));
	}

}

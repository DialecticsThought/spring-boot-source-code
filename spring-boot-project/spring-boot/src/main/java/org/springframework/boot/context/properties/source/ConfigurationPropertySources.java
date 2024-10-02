/*
 * Copyright 2012-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.properties.source;

import java.util.Collections;
import java.util.stream.Stream;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.ConfigurablePropertyResolver;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySource.StubPropertySource;
import org.springframework.core.env.PropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.util.Assert;

/**
 * Provides access to {@link ConfigurationPropertySource ConfigurationPropertySources}.
 *
 * @author Phillip Webb
 * @since 2.0.0
 */
public final class ConfigurationPropertySources {

	/**
	 * The name of the {@link PropertySource} {@link #attach(Environment) adapter}.
	 */
	private static final String ATTACHED_PROPERTY_SOURCE_NAME = "configurationProperties";

	private ConfigurationPropertySources() {
	}

	/**
	 * Create a new {@link PropertyResolver} that resolves property values against an
	 * underlying set of {@link PropertySources}. Provides an
	 * {@link ConfigurationPropertySource} aware and optimized alternative to
	 * {@link PropertySourcesPropertyResolver}.
	 * @param propertySources the set of {@link PropertySource} objects to use
	 * @return a {@link ConfigurablePropertyResolver} implementation
	 * @since 2.5.0
	 */
	public static ConfigurablePropertyResolver createPropertyResolver(MutablePropertySources propertySources) {
		return new ConfigurationPropertySourcesPropertyResolver(propertySources);
	}

	/**
	 * Determines if the specific {@link PropertySource} is the
	 * {@link ConfigurationPropertySource} that was {@link #attach(Environment) attached}
	 * to the {@link Environment}.
	 * @param propertySource the property source to test
	 * @return {@code true} if this is the attached {@link ConfigurationPropertySource}
	 */
	public static boolean isAttachedConfigurationPropertySource(PropertySource<?> propertySource) {
		return ATTACHED_PROPERTY_SOURCE_NAME.equals(propertySource.getName());
	}

	/**
	 * Attach a {@link ConfigurationPropertySource} support to the specified
	 * {@link Environment}. Adapts each {@link PropertySource} managed by the environment
	 * to a {@link ConfigurationPropertySource} and allows classic
	 * {@link PropertySourcesPropertyResolver} calls to resolve using
	 * {@link ConfigurationPropertyName configuration property names}.
	 * <p>
	 * The attached resolver will dynamically track any additions or removals from the
	 * underlying {@link Environment} property sources.
	 * @param environment the source environment (must be an instance of
	 * {@link ConfigurableEnvironment})
	 * @see #get(Environment)
	 */
	public static void attach(Environment environment) {
		// 确保传入的 environment 是 ConfigurableEnvironment 类型的实例。
		// ConfigurableEnvironment 是 Environment 的子接口，提供了更多对 PropertySource 管理和操作的方法
		Assert.isInstanceOf(ConfigurableEnvironment.class, environment);
		// 将传入的 environment 转换为 ConfigurableEnvironment，
		// 并通过 getPropertySources() 方法获取其 PropertySources（属性源）。
		// MutablePropertySources 是一个 PropertySource 的集合，管理着 Environment 中所有的属性源。
		MutablePropertySources sources = ((ConfigurableEnvironment) environment).getPropertySources();
		// 调用 getAttached(sources) 方法，
		// 检查当前 sources 是否已经附加了一个 ConfigurationPropertySourcesPropertySource，
		// 用于标识该 PropertySource 是否已经被附加过。
		// 如果已经附加过，attached 会返回对应的 PropertySource，否则返回 null
		PropertySource<?> attached = getAttached(sources);
		// 检查 attached 是否为 null，或者是否与当前的 sources 是相关联的。
		// 如果没有附加，或者附加的 PropertySource 已经和 sources 不匹配，则进入该条件块
		if (attached == null || !isUsingSources(attached, sources)) {
			// 如果条件为真，创建一个新的 ConfigurationPropertySourcesPropertySource，并将 sources 传递给新的 SpringConfigurationPropertySources 对象
			// ConfigurationPropertySourcesPropertySource 是一个特殊的 PropertySource，用于适配 ConfigurationPropertySource
			// ATTACHED_PROPERTY_SOURCE_NAME 是附加的 PropertySource 的名称，用于识别该 PropertySource
			attached = new ConfigurationPropertySourcesPropertySource(ATTACHED_PROPERTY_SOURCE_NAME,
					new SpringConfigurationPropertySources(sources));
		}
		// 无论是否是新创建的 attached，都将名称为 ATTACHED_PROPERTY_SOURCE_NAME 的 PropertySource 从 sources 中移除
		// 如果该 PropertySource 已经存在，就先移除它，以避免重复附加
		sources.remove(ATTACHED_PROPERTY_SOURCE_NAME);
		// 将新创建的或现有的 attached PropertySource 添加到 sources 的最前面。addFirst() 方法确保这个 PropertySource 在所有属性源的优先级最高
		sources.addFirst(attached);
	}

	private static boolean isUsingSources(PropertySource<?> attached, MutablePropertySources sources) {
		return attached instanceof ConfigurationPropertySourcesPropertySource
				&& ((SpringConfigurationPropertySources) attached.getSource()).isUsingSources(sources);
	}

	static PropertySource<?> getAttached(MutablePropertySources sources) {
		return (sources != null) ? sources.get(ATTACHED_PROPERTY_SOURCE_NAME) : null;
	}

	/**
	 * Return a set of {@link ConfigurationPropertySource} instances that have previously
	 * been {@link #attach(Environment) attached} to the {@link Environment}.
	 * @param environment the source environment (must be an instance of
	 * {@link ConfigurableEnvironment})
	 * @return an iterable set of configuration property sources
	 * @throws IllegalStateException if not configuration property sources have been
	 * attached
	 */
	public static Iterable<ConfigurationPropertySource> get(Environment environment) {
		Assert.isInstanceOf(ConfigurableEnvironment.class, environment);
		MutablePropertySources sources = ((ConfigurableEnvironment) environment).getPropertySources();
		ConfigurationPropertySourcesPropertySource attached = (ConfigurationPropertySourcesPropertySource) sources
			.get(ATTACHED_PROPERTY_SOURCE_NAME);
		if (attached == null) {
			return from(sources);
		}
		return attached.getSource();
	}

	/**
	 * Return {@link Iterable} containing a single new {@link ConfigurationPropertySource}
	 * adapted from the given Spring {@link PropertySource}.
	 * @param source the Spring property source to adapt
	 * @return an {@link Iterable} containing a single newly adapted
	 * {@link SpringConfigurationPropertySource}
	 */
	public static Iterable<ConfigurationPropertySource> from(PropertySource<?> source) {
		return Collections.singleton(ConfigurationPropertySource.from(source));
	}

	/**
	 * Return {@link Iterable} containing new {@link ConfigurationPropertySource}
	 * instances adapted from the given Spring {@link PropertySource PropertySources}.
	 * <p>
	 * This method will flatten any nested property sources and will filter all
	 * {@link StubPropertySource stub property sources}. Updates to the underlying source,
	 * identified by changes in the sources returned by its iterator, will be
	 * automatically tracked. The underlying source should be thread safe, for example a
	 * {@link MutablePropertySources}
	 * @param sources the Spring property sources to adapt
	 * @return an {@link Iterable} containing newly adapted
	 * {@link SpringConfigurationPropertySource} instances
	 */
	public static Iterable<ConfigurationPropertySource> from(Iterable<PropertySource<?>> sources) {
		return new SpringConfigurationPropertySources(sources);
	}

	private static Stream<PropertySource<?>> streamPropertySources(PropertySources sources) {
		return sources.stream()
			.flatMap(ConfigurationPropertySources::flatten)
			.filter(ConfigurationPropertySources::isIncluded);
	}

	private static Stream<PropertySource<?>> flatten(PropertySource<?> source) {
		if (source.getSource() instanceof ConfigurableEnvironment) {
			return streamPropertySources(((ConfigurableEnvironment) source.getSource()).getPropertySources());
		}
		return Stream.of(source);
	}

	private static boolean isIncluded(PropertySource<?> source) {
		return !(source instanceof StubPropertySource)
				&& !(source instanceof ConfigurationPropertySourcesPropertySource);
	}

}

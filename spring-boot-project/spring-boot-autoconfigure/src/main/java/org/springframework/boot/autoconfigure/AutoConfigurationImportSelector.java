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

package org.springframework.boot.autoconfigure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.swing.Spring;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * {@link DeferredImportSelector} to handle {@link EnableAutoConfiguration
 * auto-configuration}. This class can also be subclassed if a custom variant of
 * {@link EnableAutoConfiguration @EnableAutoConfiguration} is needed.
 *
 * <pre>
 *  TODO
 *    DeferredImportSelector
 * 		作用: 允许在 Spring 容器完成初步初始化后再动态导入其他配置类。
 * 			这与普通的 ImportSelector 相比，DeferredImportSelector 会推迟到容器创建过程中更晚的阶段，
 * 			确保所有的配置类都能按正确的顺序被处理。
 * 	  BeanClassLoaderAware
 * 		作用: 使实现类能够感知到 ClassLoader 实例，并在需要时使用它来加载类或资源。
 * 	  ResourceLoaderAware
 * 		作用: 允许实现类获得 ResourceLoader 的引用，能够通过它加载外部资源（如配置文件、类路径下的资源等）
 * 	  BeanFactoryAware
 *		 作用: 允许实现类获得 BeanFactory 的引用，使其能够访问或操作 Spring 容器中的所有 Bean。
 *	  EnvironmentAware
 * 		作用: 允许实现类访问到 Spring 的 Environment 对象，
 * 			从而可以根据环境配置（如 application.properties 或 application.yml 文件中的属性）做出动态决策。
 * 	   Ordered
 * 		作用: 定义对象的排序顺序。实现该接口的类会有一个整数值来指定它们的优先级，数字越小优先级越高
 * </pre>
 *
 * <pre>
 * TODO
 * 	在 getAttributes() 和 getAnnotationClass() 这两个方法中没有直接进行类扫描。
 * 	实际上，类扫描的步骤是在 Spring 的其他部分完成的。getAttributes() 只是从传入的 AnnotationMetadata 中提取已经被扫描到的类的注解信息。
 * 	扫描过程的上下文
 * 	Spring Boot 执行类扫描并处理自动配置的过程，通常涉及以下几个步骤：
 * 	 1.Spring Boot 启动时，会自动扫描你的应用程序上下文中的配置类。
 * 		这个过程通常是由 @SpringBootApplication 或 @ComponentScan 注解触发的，
 * 		它会找到应用程序中的所有类（包括带有 @EnableAutoConfiguration 注解的类）。
 * 	 2.AnnotationMetadata 是类的元数据的一部分，
 * 		 当 Spring 扫描类时，它会为每个类生成 AnnotationMetadata，这个对象包含了类的注解、方法等信息。
 *	 3.AutoConfigurationImportSelector：当扫描到带有 @EnableAutoConfiguration 注解的类时，
 *	 		Spring 会触发 AutoConfigurationImportSelector 来处理这个注解，
 *	 		并基于 @EnableAutoConfiguration 的逻辑选择需要导入的自动配置类。
 *  类扫描后传递到 getAttributes()：
 *  getAttributes() 方法是在 AutoConfigurationImportSelector 被调用时，
 * 		获取到的 AnnotationMetadata 中提取 @EnableAutoConfiguration 注解的属性。
 *  getAttributes() 只是从传入的 AnnotationMetadata 对象中提取信息，
 * 		扫描工作已经由 Spring 的上下文在更早的阶段完成。
 * </pre>
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 * @author Madhura Bhave
 * @author Moritz Halbritter
 * @since 1.3.0
 * @see EnableAutoConfiguration
 */
public class AutoConfigurationImportSelector implements DeferredImportSelector, BeanClassLoaderAware,
		ResourceLoaderAware, BeanFactoryAware, EnvironmentAware, Ordered {

	private static final AutoConfigurationEntry EMPTY_ENTRY = new AutoConfigurationEntry();

	private static final String[] NO_IMPORTS = {};

	private static final Log logger = LogFactory.getLog(AutoConfigurationImportSelector.class);

	private static final String PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";

	private ConfigurableListableBeanFactory beanFactory;

	private Environment environment;

	private ClassLoader beanClassLoader;

	private ResourceLoader resourceLoader;

	private ConfigurationClassFilter configurationClassFilter;

	/**
	 *TODO
	 *  DeferredImportSelector#selectImports() 方法的执行逻辑：
	 * 		如果实现类实现了 getImportGroup() 方法（返回值不为空且实现了DeferredImportSelector.Group），
	 * 		那么就调用 Group 类中的selectImports() 方法，
	 * 		否则调用实现类的 selectImports() 方法
	 * @param annotationMetadata
	 * @return
	 */
	@Override
	public String[] selectImports(AnnotationMetadata annotationMetadata) {
		if (!isEnabled(annotationMetadata)) {
			return NO_IMPORTS;
		}
		AutoConfigurationEntry autoConfigurationEntry = getAutoConfigurationEntry(annotationMetadata);
		return StringUtils.toStringArray(autoConfigurationEntry.getConfigurations());
	}

	@Override
	public Predicate<String> getExclusionFilter() {
		return this::shouldExclude;
	}

	private boolean shouldExclude(String configurationClassName) {
		return getConfigurationClassFilter().filter(Collections.singletonList(configurationClassName)).isEmpty();
	}

	/**
	 * TODO
	 * 	接受 AnnotationMetadata 对象作为参数，
	 * 	AnnotationMetadata 包含了当前类的注解元数据（比如类上是否有 @EnableAutoConfiguration 注解等信息）
	 *	 该方法的目的是根据元数据生成自动配置类和排除类的列表，并返回一个 AutoConfigurationEntry 对象
	 *
	 * Return the {@link AutoConfigurationEntry} based on the {@link AnnotationMetadata}
	 * of the importing {@link Configuration @Configuration} class.
	 * @param annotationMetadata the annotation metadata of the configuration class
	 * @return the auto-configurations that should be imported
	 */
	protected AutoConfigurationEntry getAutoConfigurationEntry(AnnotationMetadata annotationMetadata) {
		//  首先调用 isEnabled() 方法检查当前自动配置是否被启用。
		//  如果没有启用，直接返回一个空的 AutoConfigurationEntry 对象（EMPTY_ENTRY），表示不进行自动配置
		if (!isEnabled(annotationMetadata)) {
			return EMPTY_ENTRY;
		}
		// 从传入的 AnnotationMetadata 中提取 @EnableAutoConfiguration 注解的属性。
		// 这个属性包含了一些配置信息，例如可能的排除列表或其他配置参数
		AnnotationAttributes attributes = getAttributes(annotationMetadata);
		// TODO 重点 进入查看
		//  获取候选的自动配置类列表。
		//  这个方法会从配置文件或者类路径中加载所有符合条件的自动配置类（通常在 spring.factories 文件中定义）。
		//  这些类是可能的自动配置类，Spring 会根据条件动态选择哪些需要导入
		List<String> configurations = getCandidateConfigurations(annotationMetadata, attributes);
		// 移除 configurations 列表中的重复类。这是一个防止重复加载同一类的安全措施
		configurations = removeDuplicates(configurations);
		// 获取需要排除的自动配置类列表。
		// 这个方法会提取 @EnableAutoConfiguration 注解中的 exclude 和 excludeName 属性，和配置文件配置的spring.autoconfigure.exclude属性
		// 用户可以通过这些属性手动指定要排除的自动配置类
		// TODO 进入
		Set<String> exclusions = getExclusions(annotationMetadata, attributes);
		// 检查排除类列表中的类是否合法（例如检查排除类是否存在于候选配置类中）。
		checkExcludedClasses(configurations, exclusions);
		// 排除的类是用户在 @EnableAutoConfiguration 注解中通过 exclude 和 excludeName 指定的
		configurations.removeAll(exclusions);
		// 对剩下的候选配置类进行过滤。
		// 这个过滤器会根据当前环境或条件，排除不符合条件的自动配置类。
		// 比如某些配置类可能需要特定的依赖或环境变量，过滤器会排除那些不符合条件的类
		configurations = getConfigurationClassFilter().filter(configurations);
		// 这个方法会广播一个事件，通知监听器哪些配置类将被导入，哪些配置类被排除
		fireAutoConfigurationImportEvents(configurations, exclusions);
		// 最终返回一个新的 AutoConfigurationEntry 对象，包含两个关键的信息：
		// configurations：最终确定要导入的自动配置类列表。
		// exclusions：最终排除的自动配置类列表
		return new AutoConfigurationEntry(configurations, exclusions);
	}

	@Override
	public Class<? extends Group> getImportGroup() {
		// TODO 查看
		return AutoConfigurationGroup.class;
	}

	protected boolean isEnabled(AnnotationMetadata metadata) {
		if (getClass() == AutoConfigurationImportSelector.class) {
			return getEnvironment().getProperty(EnableAutoConfiguration.ENABLED_OVERRIDE_PROPERTY, Boolean.class, true);
		}
		return true;
	}

	/**
	 * TODO
	 * 	用于从传入的 AnnotationMetadata 对象中提取指定注解的属性。
	 * 	在默认情况下，它会提取 @EnableAutoConfiguration 注解的属性
	 * 	<pre>
	 *        @Configuration
	 *        @EnableAutoConfiguration
	 *         public class MyAppConfiguration {
	 *        }
	 * 	当 Spring 处理这个配置类时：
	 * 		getAnnotationClass() 方法返回 EnableAutoConfiguration.class，
	 * 				表示要从 MyAppConfiguration 类中提取 @EnableAutoConfiguration 的元数据。
	 * 		getAttributes(AnnotationMetadata metadata) 方法
	 * 				将会从 MyAppConfiguration 类的 AnnotationMetadata 中提取 @EnableAutoConfiguration 注解的属性。
	 * 	</pre>
	 * 	getAttributes 方法的作用就是：
	 * 		扫描所有类，然后找到那些使用了 @EnableAutoConfiguration 注解的类。
	 * 		提取这个注解的属性，并确保这个类确实使用了这个注解。
	 * 			如果类上没有 @EnableAutoConfiguration，就会抛出一个错误，提醒你可能忘记加这个注解。
	 * Return the appropriate {@link AnnotationAttributes} from the
	 * {@link AnnotationMetadata}. By default this method will return attributes for
	 * {@link #getAnnotationClass()}.
	 * @param metadata the annotation metadata
	 * @return annotation attributes
	 */
	protected AnnotationAttributes getAttributes(AnnotationMetadata metadata) {
		// 调用 getAnnotationClass() 获取要处理的注解类（默认是 EnableAutoConfiguration），
		// 然后获取它的类名（全限定名），用于后续从 AnnotationMetadata 中提取这个注解的属性。
		String name = getAnnotationClass().getName();
		// 这里调用 AnnotationMetadata.getAnnotationAttributes(name, true) 来获取指定注解类的属性。
		// name 是注解的类名（如 EnableAutoConfiguration 的全限定类名），true 表示在父类中也查找该注解。
		AnnotationAttributes attributes = AnnotationAttributes.fromMap(metadata.getAnnotationAttributes(name, true));
		Assert.notNull(attributes, () -> "No auto-configuration attributes found. Is " + metadata.getClassName()
				+ " annotated with " + ClassUtils.getShortName(name) + "?");
		return attributes;
	}

	/**
	 * Return the source annotation class used by the selector.
	 * @return the annotation class
	 */
	protected Class<?> getAnnotationClass() {
		// 返回 EnableAutoConfiguration.class，表示自动配置的源注解
		return EnableAutoConfiguration.class;
	}

	/**
	 * Return the auto-configuration class names that should be considered. By default
	 * this method will load candidates using {@link ImportCandidates} with
	 * {@link #getSpringFactoriesLoaderFactoryClass()}. For backward compatible reasons it
	 * will also consider {@link SpringFactoriesLoader} with
	 * {@link #getSpringFactoriesLoaderFactoryClass()}.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return a list of candidate configurations
	 */
	protected List<String> getCandidateConfigurations(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		// 这是 Spring 加载工厂类（例如自动配置类）的核心工具，它通过扫描类路径中的 META-INF/spring.factories 文件，获取 EnableAutoConfiguration 对应的配置类
		// getSpringFactoriesLoaderFactoryClass() 返回 EnableAutoConfiguration.class，这是自动配置类的类型，Spring Boot 用这个类型来查找与自动配置相关的候选类
		// getBeanClassLoader() 获取当前 Spring 应用的类加载器，用于从类路径中加载工厂类
		List<String> configurations = new ArrayList<>(
				SpringFactoriesLoader.loadFactoryNames(getSpringFactoriesLoaderFactoryClass(), getBeanClassLoader()));
		// Spring Framework 5.3 引入的功能，
		// 允许从 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 文件中加载更多的自动配置类
		// AutoConfiguration.class 作为类型，告诉 Spring 需要加载与自动配置相关的类
		// getBeanClassLoader() 提供类加载器，允许从类路径中读取该文件并加载候选类
		// forEach(configurations::add)：将从 AutoConfiguration.imports 文件中加载的配置类添加到 configurations 列表中，进一步补充自动配置的候选类列表
		ImportCandidates.load(AutoConfiguration.class, getBeanClassLoader()).forEach(configurations::add);
		Assert.notEmpty(configurations,
				"No auto configuration classes found in META-INF/spring.factories nor in META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports. If you "
						+ "are using a custom packaging, make sure that file is correct.");
		return configurations;
	}

	/**
	 * Return the class used by {@link SpringFactoriesLoader} to load configuration
	 * candidates.
	 * @return the factory class
	 */
	protected Class<?> getSpringFactoriesLoaderFactoryClass() {
		return EnableAutoConfiguration.class;
	}

	private void checkExcludedClasses(List<String> configurations, Set<String> exclusions) {
		List<String> invalidExcludes = new ArrayList<>(exclusions.size());
		for (String exclusion : exclusions) {
			if (ClassUtils.isPresent(exclusion, getClass().getClassLoader()) && !configurations.contains(exclusion)) {
				invalidExcludes.add(exclusion);
			}
		}
		if (!invalidExcludes.isEmpty()) {
			handleInvalidExcludes(invalidExcludes);
		}
	}

	/**
	 * Handle any invalid excludes that have been specified.
	 * @param invalidExcludes the list of invalid excludes (will always have at least one
	 * element)
	 */
	protected void handleInvalidExcludes(List<String> invalidExcludes) {
		StringBuilder message = new StringBuilder();
		for (String exclude : invalidExcludes) {
			message.append("\t- ").append(exclude).append(String.format("%n"));
		}
		throw new IllegalStateException(String.format(
				"The following classes could not be excluded because they are not auto-configuration classes:%n%s",
				message));
	}

	/**
	 * Return any exclusions that limit the candidate configurations.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return exclusions or an empty set
	 */
	protected Set<String> getExclusions(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		Set<String> excluded = new LinkedHashSet<>();
		excluded.addAll(asList(attributes, "exclude"));
		excluded.addAll(asList(attributes, "excludeName"));
		// TODO 进入
		excluded.addAll(getExcludeAutoConfigurationsProperty());
		return excluded;
	}

	/**
	 * Returns the auto-configurations excluded by the
	 * {@code spring.autoconfigure.exclude} property.
	 * @return excluded auto-configurations
	 * @since 2.3.2
	 */
	protected List<String> getExcludeAutoConfigurationsProperty() {
		// 获取当前的 Environment 对象。
		// Environment 是 Spring 提供的一个接口，用于访问应用程序的环境配置（例如系统属性、配置文件属性等）
		Environment environment = getEnvironment();
		if (environment == null) {
			return Collections.emptyList();
		}
		// ConfigurableEnvironment 是 Environment 的一个子接口，它提供了更强的绑定能力，允许通过 Binder 将配置文件中的属性与 Java 对象进行绑定
		if (environment instanceof ConfigurableEnvironment) {
			// 使用 Binder.get(environment) 方法创建一个 Binder 实例
			// 它用于从 Environment 中提取和绑定配置属性。
			// 相比直接使用 Environment.getProperty()，Binder 提供了更强大的属性绑定和转换功能
			Binder binder = Binder.get(environment);
			// binder.bind(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class)：
			// 	从 Environment 中绑定 spring.autoconfigure.exclude 属性。
			// 	PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE 是 spring.autoconfigure.exclude 配置项的常量名，
			// 	String[].class 指定了要将这个属性绑定为字符串数组
			return binder.bind(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class)
					// 如果绑定成功，将字符串数组转换为 List<String>
					.map(Arrays::asList)
					// 如果 spring.autoconfigure.exclude 属性不存在，返回一个空列表
					.orElse(Collections.emptyList());
		}
		//  如果 environment 不是 ConfigurableEnvironment 的实例（例如在某些自定义环境中），
		//  则使用传统的 getProperty() 方法从 Environment 中获取 spring.autoconfigure.exclude 属性，返回一个 String[] 数组
		String[] excludes = environment.getProperty(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class);
		return (excludes != null) ? Arrays.asList(excludes) : Collections.emptyList();
	}

	/**
	 * 加载所有实现了 AutoConfigurationImportFilter 接口的过滤器。
	 * 这些过滤器用于在 Spring Boot 的自动配置机制中对自动配置类进行过滤，决定哪些类应该被真正导入，哪些应该被排除。
	 * 过滤器提供了一种机制，可以基于当前环境条件来动态选择或排除某些自动配置类
	 * @return
	 */
	protected List<AutoConfigurationImportFilter> getAutoConfigurationImportFilters() {
		// AutoConfigurationImportFilter 是 Spring Boot 的一个接口，用于过滤自动配置类
		// 实现该接口的类会根据特定的条件（例如当前的环境或配置）决定哪些自动配置类应该被导入，哪些应该被排除
		// 通过 SpringFactoriesLoader 从 META-INF/spring.factories 文件中加载所有 AutoConfigurationImportFilter 实现类
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportFilter.class, this.beanClassLoader);
	}

	/**
	 * 获取一个 ConfigurationClassFilter 实例，该实例用于根据当前环境或条件过滤自动配置类。
	 * 在这个方法中，首先会加载所有的 AutoConfigurationImportFilter（自动配置过滤器），
	 * 并通过这些过滤器决定哪些自动配置类应该被过滤掉
	 * @return
	 */
	private ConfigurationClassFilter getConfigurationClassFilter() {
		if (this.configurationClassFilter == null) {
			// 获取所有实现了 AutoConfigurationImportFilter 接口的过滤器。
			// AutoConfigurationImportFilter 过滤器将用于过滤自动配置类，决定哪些类需要排除，哪些类需要加载
			List<AutoConfigurationImportFilter> filters = getAutoConfigurationImportFilters();
			for (AutoConfigurationImportFilter filter : filters) {
				// invokeAwareMethods(filter) 的作用是检查
				// filter 是否实现了某些感知接口（如 BeanClassLoaderAware、BeanFactoryAware 等），
				// 如果实现了这些接口，就会调用相应的感知方法，将必要的依赖注入给过滤器
				// TODO 进入
				invokeAwareMethods(filter);
			}
			// ConfigurationClassFilter 是一个关键的类，它的作用是将所有的过滤器组合起来，在自动配置类的过滤过程中应用这些过滤器
			this.configurationClassFilter = new ConfigurationClassFilter(this.beanClassLoader, filters);
		}
		return this.configurationClassFilter;
	}

	protected final <T> List<T> removeDuplicates(List<T> list) {
		return new ArrayList<>(new LinkedHashSet<>(list));
	}

	protected final List<String> asList(AnnotationAttributes attributes, String name) {
		String[] value = attributes.getStringArray(name);
		return Arrays.asList(value);
	}

	private void fireAutoConfigurationImportEvents(List<String> configurations, Set<String> exclusions) {
		List<AutoConfigurationImportListener> listeners = getAutoConfigurationImportListeners();
		if (!listeners.isEmpty()) {
			AutoConfigurationImportEvent event = new AutoConfigurationImportEvent(this, configurations, exclusions);
			for (AutoConfigurationImportListener listener : listeners) {
				invokeAwareMethods(listener);
				listener.onAutoConfigurationImportEvent(event);
			}
		}
	}

	/**
	 * TODO
	 *  将会扫描类路径中的 META-INF/spring.factories 文件，
	 *  找到所有与 AutoConfigurationImportListener 相关的实现类，
	 *  并将它们实例化成对象，返回一个 List<AutoConfigurationImportListener>
	 * TODO
	 *  AutoConfigurationImportListener 是 Spring Boot 的一个接口，
	 *  允许监听自动配置类导入和排除的事件。通常用于记录、调试或在自动配置发生时执行其他操作
	 * @return
	 */
	protected List<AutoConfigurationImportListener> getAutoConfigurationImportListeners() {
		// SpringFactoriesLoader.loadFactories(): 这个工具方法是 Spring 用来从类路径中的 META-INF/spring.factories 文件加载指定类型工厂类的工具。
		// 第一个参数是 AutoConfigurationImportListener.class，它指定要加载的类类型，也就是 AutoConfigurationImportListener。
		// 第二个参数是 this.beanClassLoader，它提供类加载器，用于从类路径中加载这些实现类。
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportListener.class, this.beanClassLoader);
	}

	private void invokeAwareMethods(Object instance) {
		if (instance instanceof Aware) {
			if (instance instanceof BeanClassLoaderAware) {
				((BeanClassLoaderAware) instance).setBeanClassLoader(this.beanClassLoader);
			}
			if (instance instanceof BeanFactoryAware) {
				((BeanFactoryAware) instance).setBeanFactory(this.beanFactory);
			}
			if (instance instanceof EnvironmentAware) {
				((EnvironmentAware) instance).setEnvironment(this.environment);
			}
			if (instance instanceof ResourceLoaderAware) {
				((ResourceLoaderAware) instance).setResourceLoader(this.resourceLoader);
			}
		}
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory);
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	protected final ConfigurableListableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	protected ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	protected final Environment getEnvironment() {
		return this.environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	protected final ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	/**
	 * 它结合 AutoConfigurationImportFilter 的实现，根据当前的 AutoConfigurationMetadata 来决定哪些自动配置类应该被排除
	 */
	private static class ConfigurationClassFilter {
		// 用于存储从 AutoConfigurationMetadataLoader 加载的自动配置元数据（如配置类的优先级、依赖等）
		private final AutoConfigurationMetadata autoConfigurationMetadata;
		// 存储了实现了 AutoConfigurationImportFilter 接口的过滤器列表。这些过滤器将被用来决定哪些自动配置类需要排除
		private final List<AutoConfigurationImportFilter> filters;

		ConfigurationClassFilter(ClassLoader classLoader, List<AutoConfigurationImportFilter> filters) {
			// ：通过 ClassLoader 加载自动配置的元数据。
			// 这些元数据在 META-INF/spring-autoconfigure-metadata.properties 中定义，包含每个自动配置类的条件信息
			this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(classLoader);
			this.filters = filters;
		}

		List<String> filter(List<String> configurations) {
			// 记录方法执行的开始时间，用于后续计算过滤过程所耗费的时间
			long startTime = System.nanoTime();
			// 将 configurations 列表转换为字符串数组，方便后续过滤时修改数组中的元素
			String[] candidates = StringUtils.toStringArray(configurations);
			// 用于跟踪是否有任何类被过滤掉。如果有类被过滤掉，这个变量将被设置为 true
			boolean skipped = false;
			for (AutoConfigurationImportFilter filter : this.filters) {
				// 对每个过滤器调用 match() 方法，传入 candidates（即候选的自动配置类数组）和 autoConfigurationMetadata（自动配置的元数据）
				boolean[] match = filter.match(candidates, this.autoConfigurationMetadata);
				// match() 方法返回一个布尔数组 boolean[] match，其中 true 表示该类通过过滤器，而 false 表示该类被过滤掉
				for (int i = 0; i < match.length; i++) {
					// 如果 match[i] 为 false，
					if (!match[i]) {
						// 则将对应的 candidates[i] 置为 null，表示该候选类被过滤掉，
						candidates[i] = null;
						// 并将 skipped 设置为 true，以标识发生了过滤
						skipped = true;
					}
				}
			}
			//如果没有类被过滤（即 skipped 为 false），直接返回原始的 configurations 列表，因为没有任何类被排除
			if (!skipped) {
				return configurations;
			}
			// 遍历 candidates 数组，将非 null 的候选类添加到 result 列表中，构建最终的过滤结果
			List<String> result = new ArrayList<>(candidates.length);
			for (String candidate : candidates) {
				if (candidate != null) {
					result.add(candidate);
				}
			}
			if (logger.isTraceEnabled()) {
				int numberFiltered = configurations.size() - result.size();
				logger.trace("Filtered " + numberFiltered + " auto configuration class in "
						+ TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime) + " ms");
			}
			return result;
		}

	}

	/**
	 * TODO 查看Spring源码中org.springframework.context.annotation.DeferredImportSelector
	 *
	 */
	private static class AutoConfigurationGroup
			implements DeferredImportSelector.Group, BeanClassLoaderAware, BeanFactoryAware, ResourceLoaderAware {
		/**
		 * String 是配置类的全限定名，
		 * AnnotationMetadata 是使用了 @Import 注解的类的元数据信息。
		 */
		private final Map<String, AnnotationMetadata> entries = new LinkedHashMap<>();

		/**
		 * 每个 AutoConfigurationEntry 包含要导入的配置类和排除的类
		 */
		private final List<AutoConfigurationEntry> autoConfigurationEntries = new ArrayList<>();

		/**
		 * 保存 ClassLoader 的引用，用于加载类
		 */
		private ClassLoader beanClassLoader;

		/**
		 * 保存  beanFactory 的引用，用于加载类
		 */
		private BeanFactory beanFactory;

		/**
		 * 保存 ResourceLoader 的引用，用于加载外部资源
		 */
		private ResourceLoader resourceLoader;

		/**
		 * 保存自动配置的元数据信息（延迟加载），通过它可以访问自动配置类的元数据，例如优先级信息
		 */
		private AutoConfigurationMetadata autoConfigurationMetadata;

		@Override
		public void setBeanClassLoader(ClassLoader classLoader) {
			this.beanClassLoader = classLoader;
		}

		@Override
		public void setBeanFactory(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public void setResourceLoader(ResourceLoader resourceLoader) {
			this.resourceLoader = resourceLoader;
		}

		/**
		 * <pre>
		 *     假设我们有一个 MainConfiguration 类，它使用了 @Import 来导入自动配置类
		 *     @Configuration
		 *        @Import(AutoConfigurationImportSelector.class)
		 *        public class MainConfiguration {
		 *     		// 其他 Bean 定义
		 *        }
		 * 		AutoConfigurationImportSelector 选择了两个自动配置类，假设它们是：
		 * 			com.example.MyAutoConfiguration
		 * 			com.example.OtherAutoConfiguration
		 * 		在 process() 方法中，以下事情发生：
		 * 			1.annotationMetadata 是 MainConfiguration 类的注解元数据。
		 * 			2.autoConfigurationEntry.getConfigurations() 返回两个类名："com.example.MyAutoConfiguration" 和 "com.example.OtherAutoConfiguration"。
		 * 			3.通过 putIfAbsent()，这两个类名分别与 MainConfiguration 的 annotationMetadata 关联，并被存入 entries 中。
		 * 		最终 entries 会像这样存储：
		 * 			1.Key: "com.example.MyAutoConfiguration"
		 * 			2.Value: MainConfiguration 的 annotationMetadata
		 *			1.Key: "com.example.OtherAutoConfiguration"
		 * 			2.Value: MainConfiguration 的 annotationMetadata
		 * 		如何获取 Key 和 Value：
		 * 			Key 是从 autoConfigurationEntry.getConfigurations() 中获取的，这是自动配置类的类名。
		 * 			Value 是通过传递给 process() 方法的 annotationMetadata，它代表声明 @Import 的类（例如 MainConfiguration）的元数据。
		 *
		 * </pre>
		 * @param annotationMetadata
		 * @param deferredImportSelector
		 */
		@Override
		public void process(AnnotationMetadata annotationMetadata, DeferredImportSelector deferredImportSelector) {
			Assert.state(deferredImportSelector instanceof AutoConfigurationImportSelector,
					() -> String.format("Only %s implementations are supported, got %s",
							AutoConfigurationImportSelector.class.getSimpleName(),
							deferredImportSelector.getClass().getName()));
			// 包含需要导入的配置类的列表（configurations）和需要排除的类（exclusions）
			AutoConfigurationEntry autoConfigurationEntry = ((AutoConfigurationImportSelector) deferredImportSelector)
					.getAutoConfigurationEntry(annotationMetadata);
			this.autoConfigurationEntries.add(autoConfigurationEntry);
			// // autoConfigurationEntry.getConfigurations() 返回一个 List<String>，其中包含自动配置类的类名列表
			for (String importClassName : autoConfigurationEntry.getConfigurations()) {
				// 对于每个导入的类名（importClassName），putIfAbsent() 方法将它与当前类的注解元数据（annotationMetadata）关联
				this.entries.putIfAbsent(importClassName, annotationMetadata);
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			if (this.autoConfigurationEntries.isEmpty()) {
				return Collections.emptyList();
			}
			// 对所有 AutoConfigurationEntry 进行流处理
			Set<String> allExclusions = this.autoConfigurationEntries.stream()
					// 提取每个条目的排除类集合
					.map(AutoConfigurationEntry::getExclusions)
					// 将所有排除的类展平为一个单一的流
					.flatMap(Collection::stream)
					// 将所有排除的类收集到一个 Set 中，确保没有重复的排除项
					.collect(Collectors.toSet());
			Set<String> processedConfigurations = this.autoConfigurationEntries.stream()
					// 提取每个条目的配置类集合
					.map(AutoConfigurationEntry::getConfigurations)
					// 将所有配置类展平为一个单一的流
					.flatMap(Collection::stream)
					// 收集到一个 LinkedHashSet 中，用于保持顺序并确保没有重复的配置类
					.collect(Collectors.toCollection(LinkedHashSet::new));
			// 从要导入的配置类集合中移除之前收集到的所有排除类。这样就只剩下需要导入的类
			processedConfigurations.removeAll(allExclusions);
			// 对配置类进行排序
			// getAutoConfigurationMetadata() 返回自动配置的元数据（如优先级信息）
			// .stream()：将排序后的配置类转换为流处理
			return sortAutoConfigurations(processedConfigurations, getAutoConfigurationMetadata()).stream()
					// 于每个配置类名，创建一个新的 Entry 对象。importClassName 是配置类的全限定名，this.entries.get(importClassName) 获取与该配置类名关联的注解元数据
					.map((importClassName) -> new Entry(this.entries.get(importClassName), importClassName))
					.collect(Collectors.toList());
		}

		private AutoConfigurationMetadata getAutoConfigurationMetadata() {
			if (this.autoConfigurationMetadata == null) {
				this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(this.beanClassLoader);
			}
			return this.autoConfigurationMetadata;
		}

		private List<String> sortAutoConfigurations(Set<String> configurations,
				AutoConfigurationMetadata autoConfigurationMetadata) {
			return new AutoConfigurationSorter(getMetadataReaderFactory(), autoConfigurationMetadata)
					.getInPriorityOrder(configurations);
		}

		private MetadataReaderFactory getMetadataReaderFactory() {
			try {
				return this.beanFactory.getBean(SharedMetadataReaderFactoryContextInitializer.BEAN_NAME,
						MetadataReaderFactory.class);
			}
			catch (NoSuchBeanDefinitionException ex) {
				return new CachingMetadataReaderFactory(this.resourceLoader);
			}
		}

	}

	protected static class AutoConfigurationEntry {
		/**
		 * 它保存了被选中的自动配置类的类名列表
		 * configurations 表示最终需要被导入到 Spring 容器中的自动配置类的名称
		 */
		private final List<String> configurations;

		/**
		 * 保存了那些被排除的配置类的类名
		 * 表示在自动配置过程中需要排除的配置类
		 */
		private final Set<String> exclusions;

		private AutoConfigurationEntry() {
			this.configurations = Collections.emptyList();
			this.exclusions = Collections.emptySet();
		}

		/**
		 * Create an entry with the configurations that were contributed and their
		 * exclusions.
		 * @param configurations the configurations that should be imported
		 * @param exclusions the exclusions that were applied to the original list
		 */
		AutoConfigurationEntry(Collection<String> configurations, Collection<String> exclusions) {
			this.configurations = new ArrayList<>(configurations);
			this.exclusions = new HashSet<>(exclusions);
		}

		public List<String> getConfigurations() {
			return this.configurations;
		}

		public Set<String> getExclusions() {
			return this.exclusions;
		}

	}

}

/*
 * Copyright 2012-2019 the original author or authors.
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

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;
import java.util.Set;

import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.StringUtils;

/**
 * Internal utility used to load {@link AutoConfigurationMetadata}.
 *
 * @author Phillip Webb
 */
final class AutoConfigurationMetadataLoader {

	protected static final String PATH = "META-INF/spring-autoconfigure-metadata.properties";

	private AutoConfigurationMetadataLoader() {
	}

	static AutoConfigurationMetadata loadMetadata(ClassLoader classLoader) {
		return loadMetadata(classLoader, PATH);
	}

	/**
	 *
	 * @param classLoader 用于从指定的类路径加载资源的类加载器
	 * @param path 指定要加载的元数据文件的路径，通常是 META-INF/spring-autoconfigure-metadata.properties
	 * @return
	 */
	static AutoConfigurationMetadata loadMetadata(ClassLoader classLoader, String path) {
		try {
			// 如果 classLoader 不为 null，则使用 classLoader.getResources(path) 方法获取路径为 path 的所有资源文件的 URL，可能会有多个文件（例如从不同的 jar 包中加载）。
			// 如果 classLoader 为 null，则使用系统类加载器 ClassLoader.getSystemResources(path) 来获取资源文件。
			// 返回的 Enumeration<URL> 是一个 URL 枚举，包含所有位于指定路径的资源文件的 URL
			Enumeration<URL> urls = (classLoader != null) ? classLoader.getResources(path)
					: ClassLoader.getSystemResources(path);
			// 创建一个新的 Properties 对象，用于存储从指定路径加载的所有属性。属性文件的内容会在后面被加载并放入这个 Properties 对象中
			Properties properties = new Properties();
			while (urls.hasMoreElements()) {// 遍历 urls 枚举，判断是否还有未处理的资源 URL
				// urls.nextElement()：获取下一个资源文件的 URL。
				// new UrlResource(urls.nextElement())：将 URL 包装为 UrlResource 对象，这样可以方便地使用 PropertiesLoaderUtils 读取文件内容。
				// PropertiesLoaderUtils.loadProperties()：从 UrlResource 中加载属性文件，并将其转换为 Properties 对象。
				// properties.putAll()：将加载的属性文件内容合并到之前创建的 properties 对象中。
				// 如果有多个 META-INF/spring-autoconfigure-metadata.properties 文件，它们的内容会被合并
				properties.putAll(PropertiesLoaderUtils.loadProperties(new UrlResource(urls.nextElement())));
			}
			return loadMetadata(properties);
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Unable to load @ConditionalOnClass location [" + path + "]", ex);
		}
	}

	static AutoConfigurationMetadata loadMetadata(Properties properties) {
		return new PropertiesAutoConfigurationMetadata(properties);
	}

	/**
	 * {@link AutoConfigurationMetadata} implementation backed by a properties file.
	 *
	 * <pre>
	 * PATH = META-INF/spring-autoconfigure-metadata.properties 文件内容
	 * TODO
	 *  # Spring Boot 自动配置元数据
	 *  # 定义类 com.example.MyAutoConfiguration
	 *  com.example.MyAutoConfiguration.ConditionalOnClass=javax.sql.DataSource
	 *  com.example.MyAutoConfiguration.ConditionalOnProperty=spring.datasource.enabled
	 *  com.example.MyAutoConfiguration.ConditionalOnMissingBean=org.springframework.jdbc.core.JdbcTemplate
	 *  # 定义类 com.example.AnotherAutoConfiguration
	 *  com.example.AnotherAutoConfiguration.ConditionalOnClass=org.springframework.jms.core.JmsTemplate
	 *  com.example.AnotherAutoConfiguration.ConditionalOnBean=org.springframework.jms.config.JmsListenerContainerFactory
	 *  # 定义类 com.example.UnwantedAutoConfiguration
	 *  com.example.UnwantedAutoConfiguration.Excluded=true
	 *  解释：
	 * 	1.com.example.MyAutoConfiguration:
	 * 		ConditionalOnClass=javax.sql.DataSource: 表示该自动配置类只有在类路径中存在 javax.sql.DataSource 时才会生效。
	 * 		ConditionalOnProperty=spring.datasource.enabled: 该类的自动配置依赖于配置属性 spring.datasource.enabled 的值为 true。
	 *		 ConditionalOnMissingBean=org.springframework.jdbc.core.JdbcTemplate: 该类只有在 Spring 容器中缺少 JdbcTemplate Bean 时才会被自动配置。
	 * 	2.com.example.AnotherAutoConfiguration:
	 * 		ConditionalOnClass=org.springframework.jms.core.JmsTemplate: 该自动配置类只有在类路径中存在 JmsTemplate 时才会生效。
	 * 		ConditionalOnBean=org.springframework.jms.config.JmsListenerContainerFactory: 该类的自动配置依赖于 JmsListenerContainerFactory Bean 的存在。
	 * 	3.com.example.UnwantedAutoConfiguration:
	 * 		Excluded=true: 该自动配置类明确标记为被排除，Spring Boot 在启动时会跳过该自动配置类的加载。
	 *  如何使用这些元数据
	 * 	Spring Boot 在自动配置过程中会查阅这些元数据信息，决定是否加载对应的自动配置类。例如：
	 * 		当 Spring Boot 启动时，如果类路径中没有 javax.sql.DataSource，那么 com.example.MyAutoConfiguration 就不会被加载。
	 * 		如果配置文件中设置了 spring.datasource.enabled=false，同样会跳过 com.example.MyAutoConfiguration 的加载。
	 * 		如果 JdbcTemplate Bean 已经存在，则 com.example.MyAutoConfiguration 也不会被加载。
	 *
	 * PropertiesAutoConfigurationMetadata 类就是从 META-INF/spring-autoconfigure-metadata.properties 文件中读取这些元数据，
	 * 并根据这些条件快速决定哪些自动配置类应该被加载或跳过。
	 * </pre>
	 */
	private static class PropertiesAutoConfigurationMetadata implements AutoConfigurationMetadata {
		// 用于存储从 Properties 文件加载的键值对
		// 包含了从 META-INF/spring-autoconfigure-metadata.properties 文件中加载的键值对
		private final Properties properties;

		PropertiesAutoConfigurationMetadata(Properties properties) {
			this.properties = properties;
		}

		/**
		 * 检查 Properties 对象中是否包含以 className 为键的条目。返回 true 表示该自动配置类已经有相关元数据信息，表明它已被处理
		 * @param className the source class
		 * @return
		 */
		@Override
		public boolean wasProcessed(String className) {
			return this.properties.containsKey(className);
		}

		/**
		 * 它接受自动配置类的类名和键，调用另一个重载方法 getInteger(className, key, null)
		 * @param className the source class
		 * @param key the meta-data key
		 * @return
		 */
		@Override
		public Integer getInteger(String className, String key) {
			return getInteger(className, key, null);
		}

		/**
		 * 调用 get(className, key) 方法来获取以 className.key 为键的值。
		 * 如果获取到的 value 不为 null，则将其转换为 Integer 并返回；如果 value 为 null，则返回 defaultValue
		 * @param className the source class
		 * @param key the meta-data key
		 * @param defaultValue the default value
		 * @return
		 */
		@Override
		public Integer getInteger(String className, String key, Integer defaultValue) {
			String value = get(className, key);
			return (value != null) ? Integer.valueOf(value) : defaultValue;
		}

		/**
		 * 调用另一个重载方法 getSet(className, key, null)，默认情况下，defaultValue 为 null
		 * @param className the source class
		 * @param key the meta-data key
		 * @return
		 */
		@Override
		public Set<String> getSet(String className, String key) {
			return getSet(className, key, null);
		}

		/**
		 * 调用 get(className, key) 获取以 className.key 为键的值。
		 * 如果值存在，则调用 StringUtils.commaDelimitedListToSet(value) 将逗号分隔的字符串转换为 Set<String>，否则返回 defaultValue
		 * @param className the source class
		 * @param key the meta-data key
		 * @param defaultValue the default value
		 * @return
		 */
		@Override
		public Set<String> getSet(String className, String key, Set<String> defaultValue) {
			String value = get(className, key);
			return (value != null) ? StringUtils.commaDelimitedListToSet(value) : defaultValue;
		}

		/**
		 * 调用另一个重载方法 get(className, key, null)，默认情况下，defaultValue 为 null
		 * @param className the source class
		 * @param key the meta-data key
		 * @return
		 */
		@Override
		public String get(String className, String key) {
			return get(className, key, null);
		}

		/**
		 * 构建键 className + "." + key，即通过将类名和键用 . 连接形成唯一的键。
		 * 调用 properties.getProperty() 方法从 Properties 对象中获取与该键相关联的值。
		 * 如果值存在，返回该值；如果值为 null，则返回 defaultValue
		 * @param className the source class
		 * @param key the meta-data key
		 * @param defaultValue the default value
		 * @return
		 */
		@Override
		public String get(String className, String key, String defaultValue) {
			String value = this.properties.getProperty(className + "." + key);
			return (value != null) ? value : defaultValue;
		}

	}

}

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

package org.springframework.boot.context.config;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;

import org.springframework.boot.context.config.LocationResourceLoader.ResourceType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.log.LogMessage;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * {@link ConfigDataLocationResolver} for standard locations.
 *
 * @author Madhura Bhave
 * @author Phillip Webb
 * @author Scott Frederick
 * @since 2.4.0
 */
public class StandardConfigDataLocationResolver
		implements ConfigDataLocationResolver<StandardConfigDataResource>, Ordered {
	// 定义一个静态常量 PREFIX，用于表示资源位置前缀为 "resource:"
	private static final String PREFIX = "resource:";
	// 静态常量 CONFIG_NAME_PROPERTY，代表 Spring 中配置文件的默认属性名称 "spring.config.name"
	static final String CONFIG_NAME_PROPERTY = "spring.config.name";
	// 定义一个包含默认配置文件名称的数组，默认为 "application"，
	// 表示 Spring Boot 默认使用 application.properties 或 application.yml 作为配置文件
	private static final String[] DEFAULT_CONFIG_NAMES = { "application" };
	// 用于匹配 URL 前缀的正则表达式模式（例如 http:// 或 classpath:）。这是为了确定资源位置是否是一个 URL
	private static final Pattern URL_PREFIX = Pattern.compile("^([a-zA-Z][a-zA-Z0-9*]*?:)(.*$)");
	// 另一个正则表达式模式，用于匹配包含文件扩展名提示的文件名格式
	private static final Pattern EXTENSION_HINT_PATTERN = Pattern.compile("^(.*)\\[(\\.\\w+)\\](?!\\[)$");
	// 一个静态常量，表示没有配置文件（profile）的情况
	private static final String NO_PROFILE = null;

	private final Log logger;
	// 加载 PropertySourceLoader 列表，用于加载属性源（例如 .properties 文件或 .yml 文件）
	private final List<PropertySourceLoader> propertySourceLoaders;
	// 存储配置文件名称数组
	private final String[] configNames;
	// 用于加载资源的 LocationResourceLoader 实例
	private final LocationResourceLoader resourceLoader;

	/**
	 * Create a new {@link StandardConfigDataLocationResolver} instance.
	 * @param logger the logger to use
	 * @param binder a binder backed by the initial {@link Environment}
	 * @param resourceLoader a {@link ResourceLoader} used to load resources
	 */
	public StandardConfigDataLocationResolver(Log logger, Binder binder, ResourceLoader resourceLoader) {
		this.logger = logger;
		this.propertySourceLoaders = SpringFactoriesLoader.loadFactories(PropertySourceLoader.class,
				getClass().getClassLoader());
		this.configNames = getConfigNames(binder);
		this.resourceLoader = new LocationResourceLoader(resourceLoader);
	}

	private String[] getConfigNames(Binder binder) {
		String[] configNames = binder.bind(CONFIG_NAME_PROPERTY, String[].class).orElse(DEFAULT_CONFIG_NAMES);
		for (String configName : configNames) {
			validateConfigName(configName);
		}
		return configNames;
	}

	private void validateConfigName(String name) {
		Assert.state(!name.contains("*"), () -> "Config name '" + name + "' cannot contain '*'");
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
		return true;
	}

	@Override
	public List<StandardConfigDataResource> resolve(ConfigDataLocationResolverContext context,
			ConfigDataLocation location) throws ConfigDataNotFoundException {
		return resolve(getReferences(context, location.split()));
	}

	private Set<StandardConfigDataReference> getReferences(ConfigDataLocationResolverContext context,
			ConfigDataLocation[] configDataLocations) {
		Set<StandardConfigDataReference> references = new LinkedHashSet<>();
		for (ConfigDataLocation configDataLocation : configDataLocations) {
			references.addAll(getReferences(context, configDataLocation));
		}
		return references;
	}

	private Set<StandardConfigDataReference> getReferences(ConfigDataLocationResolverContext context,
			ConfigDataLocation configDataLocation) {
		String resourceLocation = getResourceLocation(context, configDataLocation);
		try {
			if (isDirectory(resourceLocation)) {
				return getReferencesForDirectory(configDataLocation, resourceLocation, NO_PROFILE);
			}
			return getReferencesForFile(configDataLocation, resourceLocation, NO_PROFILE);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Unable to load config data from '" + configDataLocation + "'", ex);
		}
	}

	@Override
	public List<StandardConfigDataResource> resolveProfileSpecific(ConfigDataLocationResolverContext context,
			ConfigDataLocation location, Profiles profiles) {
		return resolve(getProfileSpecificReferences(context, location.split(), profiles));
	}

	private Set<StandardConfigDataReference> getProfileSpecificReferences(ConfigDataLocationResolverContext context,
			ConfigDataLocation[] configDataLocations, Profiles profiles) {
		Set<StandardConfigDataReference> references = new LinkedHashSet<>();
		for (String profile : profiles) {
			for (ConfigDataLocation configDataLocation : configDataLocations) {
				String resourceLocation = getResourceLocation(context, configDataLocation);
				references.addAll(getReferences(configDataLocation, resourceLocation, profile));
			}
		}
		return references;
	}

	private String getResourceLocation(ConfigDataLocationResolverContext context,
			ConfigDataLocation configDataLocation) {
		String resourceLocation = configDataLocation.getNonPrefixedValue(PREFIX);
		boolean isAbsolute = resourceLocation.startsWith("/") || URL_PREFIX.matcher(resourceLocation).matches();
		if (isAbsolute) {
			return resourceLocation;
		}
		ConfigDataResource parent = context.getParent();
		if (parent instanceof StandardConfigDataResource) {
			String parentResourceLocation = ((StandardConfigDataResource) parent).getReference().getResourceLocation();
			String parentDirectory = parentResourceLocation.substring(0, parentResourceLocation.lastIndexOf("/") + 1);
			return parentDirectory + resourceLocation;
		}
		return resourceLocation;
	}

	private Set<StandardConfigDataReference> getReferences(ConfigDataLocation configDataLocation,
			String resourceLocation, String profile) {
		if (isDirectory(resourceLocation)) {
			return getReferencesForDirectory(configDataLocation, resourceLocation, profile);
		}
		return getReferencesForFile(configDataLocation, resourceLocation, profile);
	}

	private Set<StandardConfigDataReference> getReferencesForDirectory(ConfigDataLocation configDataLocation,
			String directory, String profile) {
		Set<StandardConfigDataReference> references = new LinkedHashSet<>();
		for (String name : this.configNames) {
			Deque<StandardConfigDataReference> referencesForName = getReferencesForConfigName(name, configDataLocation,
					directory, profile);
			references.addAll(referencesForName);
		}
		return references;
	}

	private Deque<StandardConfigDataReference> getReferencesForConfigName(String name,
			ConfigDataLocation configDataLocation, String directory, String profile) {
		Deque<StandardConfigDataReference> references = new ArrayDeque<>();
		for (PropertySourceLoader propertySourceLoader : this.propertySourceLoaders) {
			for (String extension : propertySourceLoader.getFileExtensions()) {
				StandardConfigDataReference reference = new StandardConfigDataReference(configDataLocation, directory,
						directory + name, profile, extension, propertySourceLoader);
				if (!references.contains(reference)) {
					references.addFirst(reference);
				}
			}
		}
		return references;
	}

	private Set<StandardConfigDataReference> getReferencesForFile(ConfigDataLocation configDataLocation, String file,
			String profile) {
		Matcher extensionHintMatcher = EXTENSION_HINT_PATTERN.matcher(file);
		boolean extensionHintLocation = extensionHintMatcher.matches();
		if (extensionHintLocation) {
			file = extensionHintMatcher.group(1) + extensionHintMatcher.group(2);
		}
		for (PropertySourceLoader propertySourceLoader : this.propertySourceLoaders) {
			String extension = getLoadableFileExtension(propertySourceLoader, file);
			if (extension != null) {
				String root = file.substring(0, file.length() - extension.length() - 1);
				StandardConfigDataReference reference = new StandardConfigDataReference(configDataLocation, null, root,
						profile, (!extensionHintLocation) ? extension : null, propertySourceLoader);
				return Collections.singleton(reference);
			}
		}
		if (configDataLocation.isOptional()) {
			return Collections.emptySet();
		}
		throw new IllegalStateException("File extension is not known to any PropertySourceLoader. "
				+ "If the location is meant to reference a directory, it must end in '/' or File.separator");
	}

	private String getLoadableFileExtension(PropertySourceLoader loader, String file) {
		for (String fileExtension : loader.getFileExtensions()) {
			if (StringUtils.endsWithIgnoreCase(file, fileExtension)) {
				return fileExtension;
			}
		}
		return null;
	}

	private boolean isDirectory(String resourceLocation) {
		return resourceLocation.endsWith("/") || resourceLocation.endsWith(File.separator);
	}

	private List<StandardConfigDataResource> resolve(Set<StandardConfigDataReference> references) {
		List<StandardConfigDataResource> resolved = new ArrayList<>();
		for (StandardConfigDataReference reference : references) {
			resolved.addAll(resolve(reference));
		}
		if (resolved.isEmpty()) {
			resolved.addAll(resolveEmptyDirectories(references));
		}
		return resolved;
	}

	private Collection<StandardConfigDataResource> resolveEmptyDirectories(
			Set<StandardConfigDataReference> references) {
		Set<StandardConfigDataResource> empty = new LinkedHashSet<>();
		for (StandardConfigDataReference reference : references) {
			if (reference.getDirectory() != null) {
				empty.addAll(resolveEmptyDirectories(reference));
			}
		}
		return empty;
	}

	private Set<StandardConfigDataResource> resolveEmptyDirectories(StandardConfigDataReference reference) {
		if (!this.resourceLoader.isPattern(reference.getResourceLocation())) {
			return resolveNonPatternEmptyDirectories(reference);
		}
		return resolvePatternEmptyDirectories(reference);
	}

	private Set<StandardConfigDataResource> resolveNonPatternEmptyDirectories(StandardConfigDataReference reference) {
		Resource resource = this.resourceLoader.getResource(reference.getDirectory());
		return (resource instanceof ClassPathResource || !resource.exists()) ? Collections.emptySet()
				: Collections.singleton(new StandardConfigDataResource(reference, resource, true));
	}

	/**
	 * 主要作用是解析配置数据路径中的通配符目录，并返回一个包含这些目录的 StandardConfigDataResource 集合。
	 * 如果没有找到任何目录，且该位置不是可选的，则抛出 ConfigDataLocationNotFoundException 异常
	 * @param reference 代表一个具体的配置数据引用，包含了资源的相关信息，如配置数据位置和目录等
	 * @return 返回一个 Set<StandardConfigDataResource>，用于处理路径中带有通配符的目录
	 */
	private Set<StandardConfigDataResource> resolvePatternEmptyDirectories(StandardConfigDataReference reference) {
		// 获取与引用中的目录匹配的所有子目录
		// reference.getDirectory()获取的是该资源的目录路径，ResourceType.DIRECTORY 指定要查找的是目录
		Resource[] subdirectories = this.resourceLoader.getResources(reference.getDirectory(), ResourceType.DIRECTORY);
		// 从 reference 中获取对应的 ConfigDataLocation，该对象包含了配置数据的位置信息
		ConfigDataLocation location = reference.getConfigDataLocation();
		// 首先检查该位置是否为非可选（即配置数据是必需的）。location.isOptional() 返回 false，表示该配置位置不是可选的
		// 同时使用 ObjectUtils.isEmpty(subdirectories) 检查子目录数组是否为空。
		if (!location.isOptional() && ObjectUtils.isEmpty(subdirectories)) {
			// 如果未找到子目录且该位置是必需的，构造异常信息，表示配置数据位置没有包含任何子目录
			String message = String.format("Config data location '%s' contains no subdirectories", location);
			throw new ConfigDataLocationNotFoundException(location, message, null);
		}
		// Arrays.stream(subdirectories) 将 Resource[] 转换为流。
		// filter(Resource::exists) 过滤掉不存在的资源，确保只处理存在的资源。
		// map((resource) -> new StandardConfigDataResource(reference, resource, true))
		// 将每个 Resource 对象映射为 StandardConfigDataResource 对象，表示具体的配置数据资源。true 表示这个资源是目录。
		// collect(Collectors.toCollection(LinkedHashSet::new)) 将流中的元素收集到一个 LinkedHashSet 中，
		// 返回一个包含 StandardConfigDataResource 的集合，确保没有重复元素
		return Arrays.stream(subdirectories)
			.filter(Resource::exists)
			.map((resource) -> new StandardConfigDataResource(reference, resource, true))
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private List<StandardConfigDataResource> resolve(StandardConfigDataReference reference) {
		if (!this.resourceLoader.isPattern(reference.getResourceLocation())) {
			return resolveNonPattern(reference);
		}
		return resolvePattern(reference);
	}

	/**
	 * 方法的作用是解析不带通配符的配置数据路径，并返回与该路径对应的 StandardConfigDataResource
	 * 如果资源不存在且可以跳过，则记录日志并返回一个空的资源列表；否则，创建并返回单一的 StandardConfigDataResource 列表。
	 * @param reference 包含了资源的具体位置信息
	 * @return
	 */
	private List<StandardConfigDataResource> resolveNonPattern(StandardConfigDataReference reference) {
		// 调用 resourceLoader.getResource() 方法，传入 reference.getResourceLocation() 来获取对应位置的资源。
		// reference.getResourceLocation() 返回的是配置数据资源的位置（例如文件路径、URL 等），
		// resourceLoader.getResource() 根据这个路径返回一个 Resource 对象，代表该位置上的资源
		Resource resource = this.resourceLoader.getResource(reference.getResourceLocation());
		// 检查资源是否存在：resource.exists() 返回 false 表示该资源不存在。
		// 检查是否可以跳过该资源：reference.isSkippable() 返回 true 表示该资源是可选的，可以跳过它。
		if (!resource.exists() && reference.isSkippable()) {
			// 如果资源不存在并且允许跳过，则调用 logSkippingResource(reference) 记录日志，表示该资源被跳过了。
			logSkippingResource(reference);
			// 接着返回一个空的列表 Collections.emptyList()，表示没有任何资源需要解析
			return Collections.emptyList();
		}
		// 如果资源存在或不能跳过该资源，则调用 createConfigResourceLocation() 方法，
		// 将 reference 和 resource 传入，创建一个 StandardConfigDataResource 对象。
		// Collections.singletonList() 将该 StandardConfigDataResource 对象封装成一个包含单一元素的列表，并返回。
		// 这个列表代表已解析的资源
		return Collections.singletonList(createConfigResourceLocation(reference, resource));
	}

	/**
	 * 方法的作用是解析配置数据位置中的通配符模式，并返回匹配的 StandardConfigDataResource 列表。
	 * 它通过 resourceLoader 查找所有符合通配符的资源，并根据资源的存在情况决定是否添加到结果列表中
	 * @param reference 表示一个具体的配置数据引用，包含了资源的位置及相关属性（如是否可跳过该资源等）
	 * @return
	 */
	private List<StandardConfigDataResource> resolvePattern(StandardConfigDataReference reference) {
		// 初始化一个 List<StandardConfigDataResource>，用于存储解析后的配置数据资源
		List<StandardConfigDataResource> resolved = new ArrayList<>();
		// 使用 resourceLoader.getResources() 方法来加载资源，
		// 传入 reference.getResourceLocation() 获取带有通配符的资源路径，
		// 并指定 ResourceType.FILE 来指明查找文件资源
		for (Resource resource : this.resourceLoader.getResources(reference.getResourceLocation(), ResourceType.FILE)) {
			// 检查资源是否存在：resource.exists() 返回 false 表示该资源不存在。
			// 检查引用是否允许跳过：如果 reference.isSkippable() 为 true，表示该资源是可选的，可以跳过它。
			// 如果资源不存在且可以跳过，则调用 logSkippingResource() 方法记录日志，并跳过该资源
			if (!resource.exists() && reference.isSkippable()) {
				logSkippingResource(reference);
			}
			else {
				// 如果资源存在或该资源不能跳过，
				// 则调用 createConfigResourceLocation() 方法创建一个 StandardConfigDataResource 对象，
				// 并将其添加到 resolved 列表中
				resolved.add(createConfigResourceLocation(reference, resource));
			}
		}
		return resolved;
	}

	private void logSkippingResource(StandardConfigDataReference reference) {
		this.logger.trace(LogMessage.format("Skipping missing resource %s", reference));
	}

	private StandardConfigDataResource createConfigResourceLocation(StandardConfigDataReference reference,
			Resource resource) {
		return new StandardConfigDataResource(reference, resource);
	}

}

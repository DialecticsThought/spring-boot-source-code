/*
 * Copyright 2012-2021 the original author or authors.
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

import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;

import org.springframework.boot.BootstrapContext;
import org.springframework.boot.BootstrapRegistry;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * Strategy interface used to resolve {@link ConfigDataLocation locations} into one or
 * more {@link ConfigDataResource resources}. Implementations should be added as a
 * {@code spring.factories} entries. The following constructor parameter types are
 * supported:
 * <ul>
 * <li>{@link Log} or {@link DeferredLogFactory} - if the resolver needs deferred
 * logging</li>
 * <li>{@link Binder} - if the resolver needs to obtain values from the initial
 * {@link Environment}</li>
 * <li>{@link ResourceLoader} - if the resolver needs a resource loader</li>
 * <li>{@link ConfigurableBootstrapContext} - A bootstrap context that can be used to
 * store objects that may be expensive to create, or need to be shared
 * ({@link BootstrapContext} or {@link BootstrapRegistry} may also be used).</li>
 * </ul>
 * <p>
 * Resolvers may implement {@link Ordered} or use the {@link Order @Order} annotation. The
 * first resolver that supports the given location will be used.
 *
 * <pre>
 *  TODO
 *   作用是解析配置数据（Config Data）的位置
 *   专门用于从各种源（文件、环境变量、URL 等）解析配置数据的位置（ConfigDataLocation）
 *   配置数据可以包括 .properties、.yml 文件、外部配置服务器（
 *   ConfigDataLocationResolver 是一个接口，负责根据给定的 ConfigDataLocation 解析和加载配置源的位置。
 *   具体来说，它的作用包括：
 *      1.解析配置位置：根据传入的配置位置，判断配置数据来源是什么（如文件系统、本地资源、远程配置服务器等），并将该位置解析为实际的配置源。
 *      2.支持多种位置格式：Spring Boot 支持多种不同的配置源，如 application.properties 文件、YAML 文件、环境变量、命令行参数、外部配置服务器（如 Consul、Vault）。ConfigDataLocationResolver 负责抽象出这些不同的配置源格式。
 *      3.扩展性：你可以自定义实现这个接口来扩展 Spring Boot 的配置加载逻辑，允许应用程序从非标准的位置加载配置数据
 *   配置数据加载的流程：
 *   在 Spring Boot 启动过程中，配置数据的加载经过以下几个步骤：
 * 		初始化阶段：当 Spring Boot 应用程序启动时，会创建 ConfigDataLocationResolver 实例，它会首先检查一些基础的配置源，比如系统属性、环境变量。
 * 		解析配置位置：根据 ConfigDataLocation 中的描述，ConfigDataLocationResolver 会解析该位置。例如，如果位置是 classpath:application.yml，它会从类路径中找到并解析 application.yml 文件。
 * 		加载配置数据：解析完位置后，ConfigDataLocationResolver 会调用相应的 ConfigDataLoader 来加载实际的配置数据。
 * 	TODO
 * 	  举例说明
 * 		假设你的应用程序依赖一个远程的配置服务器来加载配置数据，你的 application.yml 文件中可能包含这样的配置：
 * 		spring:
 *   	  config:
 *    		import: "configserver:http://config.example.com"
 * 		在这种情况下，ConfigDataLocationResolver 的实现会识别 configserver: 这个前缀，
 * 		并解析出远程配置服务器的 URL（http://config.example.com），
 * 		然后从远程服务器中获取配置数据并将其加载到应用程序的 Environment 中。
 * </pre>
 *
 * @param <R> the location type
 * @author Phillip Webb
 * @author Madhura Bhave
 * @since 2.4.0
 */
public interface ConfigDataLocationResolver<R extends ConfigDataResource> {

	/**
	 * Returns if the specified location address can be resolved by this resolver.
	 * @param context the location resolver context
	 * @param location the location to check.
	 * @return if the location is supported by this resolver
	 */
	boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location);

	/**
	 * Resolve a {@link ConfigDataLocation} into one or more {@link ConfigDataResource}
	 * instances.
	 * @param context the location resolver context
	 * @param location the location that should be resolved
	 * @return a list of {@link ConfigDataResource resources} in ascending priority order.
	 * @throws ConfigDataLocationNotFoundException on a non-optional location that cannot
	 * be found
	 * @throws ConfigDataResourceNotFoundException if a resolved resource cannot be found
	 */
	List<R> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location)
			throws ConfigDataLocationNotFoundException, ConfigDataResourceNotFoundException;

	/**
	 * Resolve a {@link ConfigDataLocation} into one or more {@link ConfigDataResource}
	 * instances based on available profiles. This method is called once profiles have
	 * been deduced from the contributed values. By default this method returns an empty
	 * list.
	 * @param context the location resolver context
	 * @param location the location that should be resolved
	 * @param profiles profile information
	 * @return a list of resolved locations in ascending priority order.
	 * @throws ConfigDataLocationNotFoundException on a non-optional location that cannot
	 * be found
	 */
	default List<R> resolveProfileSpecific(ConfigDataLocationResolverContext context, ConfigDataLocation location,
			Profiles profiles) throws ConfigDataLocationNotFoundException {
		return Collections.emptyList();
	}

}

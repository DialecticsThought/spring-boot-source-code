/*
 * Copyright 2012-2020 the original author or authors.
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

package org.springframework.boot;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.util.Assert;

/**
 * Default {@link ConfigurableBootstrapContext} implementation.
 *
 * @author Phillip Webb
 * @since 2.4.0
 */
public class DefaultBootstrapContext implements ConfigurableBootstrapContext {
	// 这个 Map 存储了类类型（Class<?>）和对应的 InstanceSupplier 实例之间的映射
	// InstanceSupplier 是一个提供实例的工厂方法，支持延迟加载某些对象。
	// 在启动过程中，某些对象可能并不立即创建，而是等到需要时通过 InstanceSupplier 生成

	/**
	 * public class MyService {
	 *     public MyService() {
	 *         System.out.println("MyService is being created!");
	 *     }
	 *
	 *     public void doSomething() {
	 *         System.out.println("MyService is doing something!");
	 *     }
	 * }
	 * 使用 InstanceSupplier 注册并获取 MyService
	 * public class BootstrapContextExample {
	 *     public static void main(String[] args) {
	 *         // 创建 DefaultBootstrapContext 实例
	 *         DefaultBootstrapContext bootstrapContext = new DefaultBootstrapContext();
	 *
	 *         // 使用 InstanceSupplier 延迟注册 MyService
	 *         bootstrapContext.register(MyService.class, InstanceSupplier.of(MyService::new));
	 *
	 *         // 当需要的时候才获取 MyService 的实例
	 *         MyService myService = bootstrapContext.get(MyService.class);
	 *
	 *         // 调用 MyService 的方法
	 *         myService.doSomething();
	 *     }
	 * }
	 * InstanceSupplier.of(MyService::new):
	 * 这里我们使用了 InstanceSupplier 来包装 MyService 的构造方法。
	 * 这种方式确保 MyService 不会在注册时立即创建，
	 * 是在第一次调用 get(MyService.class) 时创建
	 */
	private final Map<Class<?>, InstanceSupplier<?>> instanceSuppliers = new HashMap<>();
	//  这个 Map 存储已经创建的实例，键是对象的类类型，值是对应的实例。
	//  一旦某个类型的对象通过 InstanceSupplier 创建，它就会被缓存到 instances 中，
	//  确保后续的获取操作不会再次创建新实例（如果是单例模式）
	private final Map<Class<?>, Object> instances = new HashMap<>();
	// 用于管理事件的发布和监听器的注册。在启动上下文关闭时，会发布事件通知监听器
	private final ApplicationEventMulticaster events = new SimpleApplicationEventMulticaster();

	@Override
	public <T> void register(Class<T> type, InstanceSupplier<T> instanceSupplier) {
		// 调用时，会将某个类型的 InstanceSupplier 注册到 instanceSuppliers 中。
		// true 表示如果已有注册，会覆盖现有的 InstanceSupplier
		register(type, instanceSupplier, true);
	}

	@Override
	public <T> void registerIfAbsent(Class<T> type, InstanceSupplier<T> instanceSupplier) {
		// 这个方法只有在没有现有的 InstanceSupplier 时才进行注册。即如果该类型已经注册过，则不会覆盖
		register(type, instanceSupplier, false);
	}

	private <T> void register(Class<T> type, InstanceSupplier<T> instanceSupplier, boolean replaceExisting) {
		Assert.notNull(type, "Type must not be null");
		Assert.notNull(instanceSupplier, "InstanceSupplier must not be null");
		// 它检查 type 和 instanceSupplier 是否为 null。
		// 通过同步块确保线程安全，然后检查是否已经注册了该类型的 InstanceSupplier
		// replaceExisting 决定了是否要替换已经注册的 InstanceSupplier。
		// 如果类型已经有实例存在（instances 中包含此类型），会抛出异常，避免已经创建的实例被重新注册。
		// 最后将 InstanceSupplier 放入 instanceSuppliers 中
		synchronized (this.instanceSuppliers) {
			boolean alreadyRegistered = this.instanceSuppliers.containsKey(type);
			if (replaceExisting || !alreadyRegistered) {
				Assert.state(!this.instances.containsKey(type), () -> type.getName() + " has already been created");
				this.instanceSuppliers.put(type, instanceSupplier);
			}
		}
	}

	@Override
	public <T> boolean isRegistered(Class<T> type) {
		// 这个方法用于检查某个类型是否已经在 instanceSuppliers 中注册。
		// 通过同步块保证线程安全
		synchronized (this.instanceSuppliers) {
			return this.instanceSuppliers.containsKey(type);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> InstanceSupplier<T> getRegisteredInstanceSupplier(Class<T> type) {
		// 获取某个类型对应的 InstanceSupplier。
		// 同样通过同步块确保线程安全，并进行类型转换
		synchronized (this.instanceSuppliers) {
			return (InstanceSupplier<T>) this.instanceSuppliers.get(type);
		}
	}

	@Override
	public void addCloseListener(ApplicationListener<BootstrapContextClosedEvent> listener) {
		// 注册一个关闭事件的监听器。
		// 当启动上下文关闭时，
		// 会触发 BootstrapContextClosedEvent，所有注册的监听器将收到通知
		this.events.addApplicationListener(listener);
	}

	@Override
	public <T> T get(Class<T> type) throws IllegalStateException {
		return getOrElseThrow(type, () -> new IllegalStateException(type.getName() + " has not been registered"));
	}

	@Override
	public <T> T getOrElse(Class<T> type, T other) {
		return getOrElseSupply(type, () -> other);
	}

	@Override
	public <T> T getOrElseSupply(Class<T> type, Supplier<T> other) {
		synchronized (this.instanceSuppliers) {
			InstanceSupplier<?> instanceSupplier = this.instanceSuppliers.get(type);
			return (instanceSupplier != null) ? getInstance(type, instanceSupplier) : other.get();
		}
	}

	@Override
	public <T, X extends Throwable> T getOrElseThrow(Class<T> type, Supplier<? extends X> exceptionSupplier) throws X {
		synchronized (this.instanceSuppliers) {
			InstanceSupplier<?> instanceSupplier = this.instanceSuppliers.get(type);
			if (instanceSupplier == null) {
				throw exceptionSupplier.get();
			}
			return getInstance(type, instanceSupplier);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T getInstance(Class<T> type, InstanceSupplier<?> instanceSupplier) {
		// 从 instances 缓存中获取类型为 type 的实例。
		// instances 是一个 Map，存储已经创建好的对象
		T instance = (T) this.instances.get(type);
		// 如果缓存中找不到实例，instance 会是 null，说明对象尚未创建
		if (instance == null) {
			// 如果 null，则意味着此实例尚未创建，需要通过 InstanceSupplier 来创建
			instance = (T) instanceSupplier.get(this);
			// 调用 InstanceSupplier 的 get 方法来创建实例。
			// 这个 InstanceSupplier 传入的是一个实现，可以是一个懒加载工厂，它会根据需要返回对应的实例
			// InstanceSupplier.get(this) 中的 this 是当前的 DefaultBootstrapContext 实例，
			// 可能会作为上下文传递给 InstanceSupplier，以便实例创建时获取相关配置或依赖
			// 这里检查 InstanceSupplier 提供的对象是否是 SINGLETON 作用域
			// getScope() 是 InstanceSupplier 的一个方法，用来判断返回的实例是单例（SINGLETON）还是每次都新建一个对象（比如 PROTOTYPE）
			// 如果是 SINGLETON（单例模式），那么这个实例会被放入 instances 缓存中，以确保下一次请求相同类型时，直接从缓存中获取该对象，避免重复创建
			if (instanceSupplier.getScope() == Scope.SINGLETON) {
				// this.instances.put(type, instance) 将新创建的实例缓存到 instances 中，保证同类型的实例只创建一次
				this.instances.put(type, instance);
			}
		}
		return instance;
	}

	/**
	 * Method to be called when {@link BootstrapContext} is closed and the
	 * {@link ApplicationContext} is prepared.
	 * @param applicationContext the prepared context
	 */
	public void close(ConfigurableApplicationContext applicationContext) {
		this.events.multicastEvent(new BootstrapContextClosedEvent(this, applicationContext));
	}

}

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

package org.springframework.boot.env;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.util.ClassUtils;

/**
 * Strategy to load '.yml' (or '.yaml') files into a {@link PropertySource}.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @since 1.0.0
 */
public class YamlPropertySourceLoader implements PropertySourceLoader {

	@Override
	public String[] getFileExtensions() {
		return new String[] { "yml", "yaml" };
	}

	@Override
	public List<PropertySource<?>> load(String name, Resource resource) throws IOException {
		// 检查类路径中是否存在 org.yaml.snakeyaml.Yaml 类，这是 YAML 解析库 SnakeYAML 的一个类。
		// 如果不存在，则说明没有找到所需的 YAML 解析器
		if (!ClassUtils.isPresent("org.yaml.snakeyaml.Yaml", getClass().getClassLoader())) {
			throw new IllegalStateException(
					"Attempted to load " + name + " but snakeyaml was not found on the classpath");
		}
		// 创建一个 OriginTrackedYamlLoader 实例，并调用其 load() 方法从指定的 resource 加载 YAML 文件。
		// 返回的结果是一个 List，每个元素是一个 Map<String, Object>，表示 YAML 文档中的内容
		List<Map<String, Object>> loaded = new OriginTrackedYamlLoader(resource).load();
		if (loaded.isEmpty()) {
			return Collections.emptyList();
		}
		// 创建一个 PropertySource 列表，初始化大小为加载的 YAML 文档数量
		List<PropertySource<?>> propertySources = new ArrayList<>(loaded.size());
		for (int i = 0; i < loaded.size(); i++) {
			// 如果加载的文档数量不止一个，构造文档编号字符串 documentNumber
			String documentNumber = (loaded.size() != 1) ? " (document #" + i + ")" : "";
			// 将每个 Map 转换为 OriginTrackedMapPropertySource 对象，并添加到 propertySources 列表中
			// 使用 Collections.unmodifiableMap() 确保这个 Map 是不可修改的
			propertySources.add(new OriginTrackedMapPropertySource(name + documentNumber,
					Collections.unmodifiableMap(loaded.get(i)), true));
		}
		return propertySources;
	}

}

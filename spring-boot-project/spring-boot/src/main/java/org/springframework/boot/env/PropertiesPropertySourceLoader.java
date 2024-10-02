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

package org.springframework.boot.env;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.boot.env.OriginTrackedPropertiesLoader.Document;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Strategy to load '.properties' files into a {@link PropertySource}.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Madhura Bhave
 * @since 1.0.0
 */
public class PropertiesPropertySourceLoader implements PropertySourceLoader {

	private static final String XML_FILE_EXTENSION = ".xml";

	@Override
	public String[] getFileExtensions() {
		return new String[] { "properties", "xml" };
	}

	@Override
	public List<PropertySource<?>> load(String name, Resource resource) throws IOException {
		// 调用 loadProperties(resource) 方法加载属性，返回一个属性的列表，每个属性以 Map 的形式存储
		List<Map<String, ?>> properties = loadProperties(resource);
		if (properties.isEmpty()) {
			return Collections.emptyList();
		}
		// 创建一个 PropertySource 列表，初始化大小为加载的属性数量
		List<PropertySource<?>> propertySources = new ArrayList<>(properties.size());
		for (int i = 0; i < properties.size(); i++) {
			// 遍历加载的属性列表，如果有多个文档，构造文档编号字符串 documentNumber
			String documentNumber = (properties.size() != 1) ? " (document #" + i + ")" : "";
			// 将每个属性映射包装成 OriginTrackedMapPropertySource 对象，并添加到 propertySources 列表中。
			// Collections.unmodifiableMap() 确保这个 Map 是不可修改的
			propertySources.add(new OriginTrackedMapPropertySource(name + documentNumber,
					Collections.unmodifiableMap(properties.get(i)), true));
		}
		return propertySources;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private List<Map<String, ?>> loadProperties(Resource resource) throws IOException {
		String filename = resource.getFilename();
		List<Map<String, ?>> result = new ArrayList<>();
		// 检查文件名是否非空，并且是否以 .xml 结尾
		if (filename != null && filename.endsWith(XML_FILE_EXTENSION)) {
			// PropertiesLoaderUtils.loadProperties(resource) 加载属性，并将结果添加到 result 列表中。
			// 此方法会将 XML 文件解析为属性映射
			result.add((Map) PropertiesLoaderUtils.loadProperties(resource));
		}
		else {
			// 如果不是 XML 文件，则使用 OriginTrackedPropertiesLoader 加载资源，返回一个 Document 列表
			List<Document> documents = new OriginTrackedPropertiesLoader(resource).load();
			// 遍历加载的 documents，将每个 Document 转换为 Map 并添加到 result 列表中
			documents.forEach((document) -> result.add(document.asMap()));
		}
		return result;
	}

}

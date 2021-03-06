/*
 * Copyright (C) 2014 Dell, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dell.doradus.olap.aggregate;

import com.dell.doradus.common.FieldDefinition;
import com.dell.doradus.olap.store.CubeSearcher;

public class MetricCollector {
	private IMetricValue m_value;
	private CubeSearcher m_searcher;
	private FieldDefinition m_fieldDef;
	
	public MetricCollector(IMetricValue value, CubeSearcher searcher, FieldDefinition fieldDef) {
		m_value = value;
		m_searcher = searcher;
		m_fieldDef = fieldDef;
	}
	
	public CubeSearcher getSearcher() { return m_searcher; }
	public FieldDefinition getFieldDefinition() { return m_fieldDef; }
	public IMetricValue get() { return m_value.newInstance(); }
	public IMetricValue convert(IMetricValue value) { return value.convert(this); }
}

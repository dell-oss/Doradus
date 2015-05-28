package com.dell.doradus.logservice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import com.dell.doradus.common.TableDefinition;
import com.dell.doradus.common.Utils;
import com.dell.doradus.logservice.store.ChunkWriter;
import com.dell.doradus.olap.OlapBatch;
import com.dell.doradus.olap.aggregate.AggregationResult;
import com.dell.doradus.olap.aggregate.MetricValueCount;
import com.dell.doradus.olap.aggregate.MetricValueSet;
import com.dell.doradus.olap.collections.strings.BstrSet;
import com.dell.doradus.olap.io.BSTR;
import com.dell.doradus.olap.store.IntList;
import com.dell.doradus.search.FieldSet;
import com.dell.doradus.search.SearchResultList;
import com.dell.doradus.search.aggregate.SortOrder;
import com.dell.doradus.search.parser.AggregationQueryBuilder;
import com.dell.doradus.search.parser.DoradusQueryBuilder;
import com.dell.doradus.search.query.Query;
import com.dell.doradus.search.util.HeapList;
import com.dell.doradus.service.db.DBService;
import com.dell.doradus.service.db.DBTransaction;
import com.dell.doradus.service.db.DColumn;
import com.dell.doradus.service.db.Tenant;

public class LogService {
    public LogService() { }

    public void createApplication(Tenant tenant, String application) {
        DBService.instance().createStoreIfAbsent(tenant, application, true);
    }

    public void deleteApplication(Tenant tenant, String application) {
        DBService.instance().deleteStoreIfPresent(tenant, application);
    }

    public void addBatch(Tenant tenant, String application, OlapBatch batch) {
        int size = batch.size();
        if(size == 0) return;
        int start = 0;
        ChunkWriter writer = new ChunkWriter();
        DBTransaction transaction = DBService.instance().startTransaction(tenant);
        while(start < size) {
            String day = batch.get(start).getId().substring(0, 10);
            int end = start + 1;
            while(end < size) {
                String nextDay = batch.get(end).getId().substring(0, 10);
                if(!nextDay.equals(day)) break;
                end++;
            }
            byte[] data = writer.writeChunk(batch, start, end - start);
            String partition = day.replace("-", "");
            String uuid = Utils.getUniqueId();
            transaction.addColumn(application, "partitions", partition, "");
            transaction.addColumn(application, "partitions_" + partition, uuid, "");
            for(BSTR field: writer.getFields()) {
                transaction.addColumn(application, "fields", field.toString(), "");
            }
            transaction.addColumn(application, partition, uuid, data);
            
            
            start = end;
        }
        DBService.instance().commit(transaction);
    }
    
    public List<String> getPartitions(Tenant tenant, String application) {
        List<String> partitions = new ArrayList<>();
        Iterator<DColumn> it = DBService.instance().getAllColumns(tenant, application, "partitions");
        if(it == null) return partitions;
        while(it.hasNext()) {
            DColumn c = it.next();
            partitions.add(c.getName());
        }
        return partitions;
    }
    
    
    public SearchResultList search(Tenant tenant, String application, LogQuery logQuery) {
        TableDefinition tableDef = Searcher.getTableDef(tenant, application);
        
        Query query = DoradusQueryBuilder.Build(logQuery.getQuery(), tableDef);
        if(logQuery.getContinueAfter() != null) {
            throw new NotImplementedException();
        }
        if(logQuery.getContinueAt() != null) {
            throw new NotImplementedException();
        }
        if(logQuery.getSkip() > 0) {
            throw new NotImplementedException();
        }
        int size = logQuery.getPageSizeWithSkip();
        FieldSet fieldSet = new FieldSet(tableDef, logQuery.getFields());
        fieldSet.expand();
        BSTR[] fields = Searcher.getFields(fieldSet);
        SortOrder[] sortOrders = AggregationQueryBuilder.BuildSortOrders(logQuery.getSortOrder(), tableDef);
        boolean bSortDescending = Searcher.isSortDescending(sortOrders);
        LogEntry current = null;
        HeapList<LogEntry> heap = new HeapList<>(size);
        int count = 0;
        List<String> partitions = getPartitions(tenant, application);
        for(String partition: partitions) {
            for(ChunkReader reader: getChunks(tenant, application, partition)) {
                for(int i = 0; i < reader.size(); i++) {
                    if(!QueryFilter.filter(query, reader, i)) continue; 
                    count++;
                    if(current == null) {
                        current = new LogEntry(fields, bSortDescending);
                    }
                    current.set(reader, i);
                    current = heap.AddEx(current);
                }
            }
        }
        
        SearchResultList list = new SearchResultList();
        list.documentsCount = count;
        LogEntry[] entries = heap.GetValues(LogEntry.class);
        for(LogEntry e: entries) {
            list.results.add(e.createSearchResult(fieldSet));
        }
        
        return list;
    }
    
    public AggregationResult aggregate(Tenant tenant, String application, LogAggregate logAggregate) {
        TableDefinition tableDef = Searcher.getTableDef(tenant, application);
        Query query = DoradusQueryBuilder.Build(logAggregate.getQuery(), tableDef);
        String field = Aggregate.getAggregateField(tableDef, logAggregate.getFields());
        
        if(field == null) {
            int count = 0;
            List<String> partitions = getPartitions(tenant, application);
            for(String partition: partitions) {
                for(ChunkReader reader: getChunks(tenant, application, partition)) {
                    for(int i = 0; i < reader.size(); i++) {
                        if(!QueryFilter.filter(query, reader, i)) continue; 
                        count++;
                    }
                }
            }
            
            AggregationResult result = new AggregationResult();
            result.documentsCount = count;
            result.summary = new AggregationResult.AggregationGroup();
            result.summary.id = null;
            result.summary.name = "*";
            result.summary.metricSet = new MetricValueSet(1);
            MetricValueCount c = new MetricValueCount();
            c.metric = count;
            result.summary.metricSet.values[0] = c; 
            return result;
        }
        else {
            IntList list = new IntList();
            BstrSet fields = new BstrSet();
            BSTR temp = new BSTR();
            int count = 0;
            List<String> partitions = getPartitions(tenant, application);
            for(String partition: partitions) {
                for(ChunkReader reader: getChunks(tenant, application, partition)) {
                    int index = reader.getFieldIndex(new BSTR(field));
                    if(index < 0) continue;
                    for(int i = 0; i < reader.size(); i++) {
                        if(!QueryFilter.filter(query, reader, i)) continue;
                        reader.getFieldValue(i, index, temp);
                        int pos = fields.add(temp);
                        if(pos == list.size()) list.add(1);
                        else list.set(pos, list.get(pos) + 1);
                        count++;
                    }
                }
            }
            
            AggregationResult result = new AggregationResult();
            result.documentsCount = count;
            result.summary = new AggregationResult.AggregationGroup();
            result.summary.id = null;
            result.summary.name = "*";
            result.summary.metricSet = new MetricValueSet(1);
            MetricValueCount c = new MetricValueCount();
            c.metric = count;
            result.summary.metricSet.values[0] = c;
            for(int i = 0; i < fields.size(); i++) {
                AggregationResult.AggregationGroup g = new AggregationResult.AggregationGroup();
                g.id = fields.get(i).toString();
                g.name = g.id.toString();
                g.metricSet = new MetricValueSet(1);
                MetricValueCount cc = new MetricValueCount();
                cc.metric = list.get(i);
                g.metricSet.values[0] = cc;
                result.groups.add(g);
            }
            result.groupsCount = result.groups.size();
            Collections.sort(result.groups);
            return result;
            
        }
    }
    
    
    public ChunkIterable getChunks(Tenant tenant, String application, String partition) {
        return new ChunkIterable(tenant, application, partition);
    }
}

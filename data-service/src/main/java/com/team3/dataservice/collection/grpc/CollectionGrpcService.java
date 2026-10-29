package com.team3.dataservice.collection.grpc;

import com.team3.contracts.collection.v1.CollectionServiceGrpc;
import com.team3.contracts.collection.v1.GetRawRecordRequest;
import com.team3.contracts.collection.v1.GetSyncJobRequest;
import com.team3.contracts.collection.v1.ListSyncJobsByDateRequest;
import com.team3.contracts.collection.v1.ListSyncJobsResponse;
import com.team3.contracts.collection.v1.RawRecord;
import com.team3.contracts.collection.v1.SaveRawRecordRequest;
import com.team3.contracts.collection.v1.StartSyncRequest;
import com.team3.contracts.collection.v1.RetrySyncRequest;
import com.team3.contracts.collection.v1.SyncJob;
import com.team3.contracts.collection.v1.SyncStartResponse;
import com.team3.dataservice.collection.domain.CollectionSource;
import com.team3.dataservice.collection.service.ManualSyncService;
import com.team3.dataservice.collection.service.RawRecordService;
import com.team3.dataservice.collection.service.SyncJobService;
import io.grpc.stub.StreamObserver;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Service;

@Service
public class CollectionGrpcService extends CollectionServiceGrpc.CollectionServiceImplBase {
    private final ManualSyncService manualSync;
    private final SyncJobService jobs;
    private final RawRecordService records;
    private final CollectionProtoMapper mapper;

    public CollectionGrpcService(ManualSyncService manualSync, SyncJobService jobs,
                                 RawRecordService records, CollectionProtoMapper mapper) {
        this.manualSync = manualSync;
        this.jobs = jobs;
        this.records = records;
        this.mapper = mapper;
    }

    @Override
    public void startSync(StartSyncRequest request, StreamObserver<SyncStartResponse> observer) {
        var source = CollectionSource.valueOf(request.getSource().name());
        var response = manualSync.start(source, request.getPageSize() == 0 ? 100 : request.getPageSize(),
                request.getMaxPages() == 0 ? 1000 : request.getMaxPages());
        observer.onNext(mapper.start(response));
        observer.onCompleted();
    }

    @Override
    public void retrySync(RetrySyncRequest request, StreamObserver<SyncStartResponse> observer) {
        observer.onNext(mapper.start(manualSync.retry(request.getJobId())));
        observer.onCompleted();
    }

    @Override
    public void listSyncJobsByDate(ListSyncJobsByDateRequest request, StreamObserver<ListSyncJobsResponse> observer) {
        LocalDate date;
        try {
            date = LocalDate.parse(request.getDate());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid date");
        }
        var result = ListSyncJobsResponse.newBuilder();
        jobs.listByDate(date).forEach(job -> result.addJobs(mapper.job(job, false)));
        observer.onNext(result.build());
        observer.onCompleted();
    }

    @Override
    public void getSyncJob(GetSyncJobRequest request, StreamObserver<SyncJob> observer) {
        observer.onNext(mapper.job(jobs.get(request.getJobId()), true));
        observer.onCompleted();
    }

    @Override
    public void saveRawRecord(SaveRawRecordRequest request, StreamObserver<RawRecord> observer) {
        observer.onNext(mapper.raw(records.save(mapper.save(request))));
        observer.onCompleted();
    }

    @Override
    public void getRawRecord(GetRawRecordRequest request, StreamObserver<RawRecord> observer) {
        observer.onNext(mapper.raw(records.get(request.getRawRecordId())));
        observer.onCompleted();
    }
}

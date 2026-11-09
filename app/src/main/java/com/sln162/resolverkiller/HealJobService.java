package com.sln162.resolverkiller;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class HealJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        BootReceiver.ensure(this, "job");
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}

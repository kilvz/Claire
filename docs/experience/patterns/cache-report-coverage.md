# The denominator of the cache hit rate

If the global Token total matches but a session that mixes providers has a low hit rate, first check whether the model adapter still keeps the reporting state of the cache field. Some responses only provide input and output, while others explicitly return a cache read of zero. Once both are converted to the same integer, an old run summary cannot tell them apart.

The session panel accumulates hit Tokens per request and only counts, in the denominator, the input of requests that explicitly report cache. When checking, mix three kinds of replies: hit, explicit zero hit, and not reported. Input that was not reported should still increase session consumption. An old summary can be backfilled with a lower bound for cumulative usage; when the coverage cannot be confirmed, keep it unknown.

The regression entry points are `MeteredModelTest.conversationUsesReportedCacheDenominatorAndLatestMainContext` and `StreamingModelsTest.anthropicStreamTakesCumulativeUsageFromMessageDelta`. The former checks the weighted sum for different input lengths and bypass calls, while the latter checks how the streaming protocol distinguishes zero hits from missing fields. This conclusion applies to metered inference requests within a session.

Verified once: the mixed-reporting unit test gets 1,300 input Tokens, cache covers 400 of them and 80 hit, for a hit rate of 20%. A two-round scripted conversation on a OnePlus phone additionally verified refresh during the run and reopening after it ended; that device case uses fixed usage and is not connected to an online provider.

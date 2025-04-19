package com.site.xidong.video;

import com.site.xidong.feedback.FeedbackReturnDTO;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class VideoWithFeedbackDTO {

    private VideoReturnDTO video;
    private FeedbackReturnDTO feedback;

}
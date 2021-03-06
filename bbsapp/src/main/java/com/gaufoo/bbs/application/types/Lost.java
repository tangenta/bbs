package com.gaufoo.bbs.application.types;

import java.util.List;

public interface Lost {

    interface AllLostsResult {}
    interface LostInfoResult {}
    interface SearchLostsResult {}
    interface CreateLostResult {}
    interface DeleteLostResult {}
    interface ClaimLostResult {}
    interface CancelClaimLostResult {}

    interface LostInfo extends
            LostInfoResult,
            CreateLostResult
    {
        String getId();
        PersonalInformation.PersonalInfo getPublisher();
        String getName();
        String getDescription();
        String getPosition();
        String getPictureURL();
        String getContact();
        Long getCreateTime();
        Long getLostTime();
        PersonalInformation.PersonalInfo getClaimer();
    }

    interface MultiLostInfos extends
            AllLostsResult,
            SearchLostsResult
    {
        Long getTotalCount();
        List<LostInfo> getLosts();
    }

    class LostInput {
        public String itemName;
        public String description;
        public String position;
        public String pictureBase64;
        public String contact;
        public Long lostTime;

        @Override
        public String toString() {
            return "LostInput{" +
                    "itemName='" + itemName + '\'' +
                    ", description='" + description + '\'' +
                    ", position='" + position + '\'' +
                    ", contact='" + contact + '\'' +
                    ", lostTime=" + lostTime +
                    '}';
        }
    }
}

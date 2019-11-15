#!/bin/bash

if "${TAG_PREVIOUS:-true}" ; then
	# If this fails, the registry might not have a existing $IMAGE_NAME:$TARGET_TAG
	if docker pull "$IMAGE_NAME:$TARGET_TAG"  ; then
		docker tag "$IMAGE_NAME:$TARGET_TAG" "$IMAGE_NAME:previous-$TARGET_TAG"
		docker push "$IMAGE_NAME:previous-$TARGET_TAG"
	fi
fi

docker pull "$IMAGE_NAME:$SOURCE_TAG"

# If we have BUILD_USER_ID data (build-user-vars-plugin)
# Tag the image whith who promoted it
if [ -n "$BUILD_USER_ID" ] ; then
	NOW=$(date "+%F_%H%M%S")
	docker tag "$IMAGE_NAME:$SOURCE_TAG" "$IMAGE_NAME:$TARGET_TAG-${BUILD_USER_ID}-${NOW}"
	docker push "$IMAGE_NAME:$TARGET_TAG-${BUILD_USER_ID}-${NOW}"
fi

docker tag "$IMAGE_NAME:$SOURCE_TAG" "$IMAGE_NAME:$TARGET_TAG"
docker push "$IMAGE_NAME:$TARGET_TAG"

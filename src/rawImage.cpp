/**
 * rawImageSimu.cpp
 *
 * \date 1/04/2010
 * \author jmcodol
 *
 *  \file rawImageSimu.cpp
 *
 *  ## Add a description here ##
 *
 * \ingroup rtslam
 */
#include "boost/shared_ptr.hpp"
#include "image/roi.hpp"

#include "rtslam/rtslamException.hpp"
#include "rtslam/rawImage.hpp"
#include "rtslam/featurePoint.hpp"

namespace jafar {
	namespace rtslam {
		using namespace std;
		using namespace jafar::image;

		///////////////////////////////////
		// RAW IMAGE CONTAINING REAL IMAGE
		///////////////////////////////////

		/*
		 * Operator << for class rawAbstract.
		 * It shows some informations
		 */
		std::ostream& operator <<(std::ostream & s, jafar::rtslam::RawImage & rawIS) {
			s << " I am a raw-data image structure" << endl;
			return s;
		}

		RawImage::RawImage() : quickHarrisDetector(5, 10.0){
		}

		void RawImage::setJafarImage(jafarImage_ptr_t img_) {
			this->img = img_;
		}

		bool RawImage::detect(const detect_method met, feature_ptr_t & featPtr,
		    ROI* roiPtr) {

			switch (met) {
				case HARRIS: {

					featurepoint_ptr_t featPntPtr(new FeaturePoint);

					if (quickHarrisDetector.detectIn(*(img.get()), featPntPtr, roiPtr)) {

						featPtr = featPntPtr;

						return true;

					} else {
						return false;
					}
				}
					break;
				default:
					JFR_ERROR(RtslamException, RtslamException::UNKNOWN_DETECTION_METHOD, "Unrecognized or inexistent feature detection method.")
					;
					break;
			}
		}

	} // namespace rtslam
} // namespace jafar

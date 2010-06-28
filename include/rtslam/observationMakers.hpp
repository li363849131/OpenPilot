/**
 * \file observationMakers.hpp
 *
 * Header file for observation makers (atoms of the obs factory).
 *
 * \author nmansard@laas.fr from croussil@laas.fr
 * \date 16/06/2010
 *
 * \ingroup rtslam
 */

#include "rtslam/observationFactory.hpp"
#include "rtslam/featurePoint.hpp"
#include "rtslam/descriptorImagePoint.hpp"

namespace jafar {
namespace rtslam {

template<class ObsType, class SenType, class LmkType,
	 SensorAbstract::type_enum SenTypeId, LandmarkAbstract::type_enum LmkTypeId>
class ImagePointObservationMaker
  : public ObservationMakerAbstract
{
	private:
  	int patchSize;
  	double dmin;
  	double reparamTh;
	public:

		ImagePointObservationMaker(int _patchSize, double _dmin = 0.0, double reparam_th = 0.0):
			ObservationMakerAbstract(SenTypeId, LmkTypeId), patchSize(_patchSize), dmin(_dmin), reparamTh(reparam_th) {}

		observation_ptr_t create(const sensor_ptr_t &senPtr, const landmark_ptr_t &lmkPtr)
		{
			boost::shared_ptr<ObsType> res(new ObsType(senPtr, lmkPtr));
			res->setup(patchSize, dmin, reparamTh);
			return res;
		}

		feature_ptr_t createFeat(const sensor_ptr_t &senPtr, const landmark_ptr_t &lmkPtr)
		{
			feature_ptr_t res(new FeatureImagePoint(patchSize, patchSize, CV_8U));
			return res;
		}

		descriptor_ptr_t createDesc(const sensor_ptr_t &senPtr, const landmark_ptr_t &lmkPtr, const feature_ptr_t &featPtr, const jblas::vec7 &senPoseInit, const observation_ptr_t &obsInitPtr)
		{
			feat_img_pnt_ptr_t featSpecPtr = SPTR_CAST<FeatureImagePoint>(featPtr);
			descriptor_ptr_t res(new DescriptorImagePoint(featSpecPtr, senPoseInit, obsInitPtr));
			return res;
		}
};

}} // namespace jafar::rtslam
